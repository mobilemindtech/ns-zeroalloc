package zeroalloc.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report
import dotty.tools.dotc.util.SrcPos

import scala.collection.mutable

/** Checks `@zeroAlloc` methods.
  *
  * Runs after `lambdaLift`, when boxing, varargs arrays, by-name thunks, captured
  * `var`s (`IntRef`) and closures are all explicit trees. See `docs/plugin.md`.
  */
class ZeroAllocPhase(unsafeBlocks: Regions, libraryInlines: Regions, settings: Settings) extends PluginPhase:
  val phaseName = "zeroAllocCheck"
  override val runsAfter = Set("lambdaLift")
  override val runsBefore = Set("genBCode")

  private final case class Violation(msg: String, pos: SrcPos)

  private enum Inferred:
    case InProgress, Ok
    case Allocates(v: Violation)

  private var annotClass: Symbol = NoSymbol
  private var externClass: Symbol = NoSymbol
  private var alwaysInlineClass: Symbol = NoSymbol

  /** Methods with a body in the current compilation unit, for inference. */
  private var localDefs: Map[Symbol, DefDef] = Map.empty
  private val inferred = mutable.Map.empty[Symbol, Inferred]
  private var inferDepth = 0
  private var sawCycle = false

  override def prepareForUnit(tree: Tree)(using Context): Context =
    annotClass = getClassIfDefined("zeroalloc.zeroAlloc")
    externClass = getClassIfDefined("scala.scalanative.unsafe.extern")
    alwaysInlineClass = getClassIfDefined("scala.scalanative.annotation.alwaysinline")
    localDefs = tree.deepFold(Map.empty[Symbol, DefDef]) {
      case (acc, dd: DefDef) if !dd.rhs.isEmpty => acc + (dd.symbol -> dd)
      case (acc, _) => acc
    }
    inferred.clear()
    ctx

  override def transformDefDef(tree: DefDef)(using Context): Tree =
    if annotClass.exists then
      val sym = tree.symbol
      if sym.hasAnnotation(annotClass) then
        checkMethod(tree)(v => report.error(s"[zeroalloc] ${v.msg}", v.pos))
      else if !sym.is(Flags.Bridge) && sym.allOverriddenSymbols.exists(_.hasAnnotation(annotClass)) then
        report.error(
          s"[zeroalloc] '${sym.name}' overrides a @zeroAlloc method and must be annotated with @zeroAlloc too",
          tree.srcPos
        )
    tree

  private def checkMethod(dd: DefDef)(sink: Violation => Unit)(using Context): Unit =
    val checker = Checker(dd.symbol, sink)
    checker.traverse(dd.rhs)
    if returnsNativeBox(dd.symbol) then
      tails(dd.rhs).foreach(checker.checkEscape(_, "is returned"))

  private class Checker(method: Symbol, sink: Violation => Unit) extends TreeTraverser:
    private def fail(msg: String, pos: SrcPos): Unit = sink(Violation(msg, pos))

    /** Local vals of a native box type, and whether they hold a newly created value. */
    private val freshLocals = mutable.Map.empty[Symbol, Boolean]

    def traverse(tree: Tree)(using Context): Unit = tree match
      case _ if unsafeBlocks.contains(tree) => ()

      // Local defs and classes are lifted out by lambdaLift; anything left is not ours to check.
      case _: DefDef | _: TypeDef => ()

      case New(tpt) => fail(s"heap allocation: 'new ${tpt.tpe.show}'", tree.srcPos)
      case _: Closure => fail("closure allocates a Function object (lambda, by-name argument or SAM)", tree.srcPos)
      case _: SeqLiteral => fail("array/varargs literal allocates on the heap", tree.srcPos)

      // Throws in library code inlined here (e.g. the size check of `stackalloc(n)`) are
      // runtime checks like a bounds check; the user's own throws need allow-throw.
      case Apply(fun, _) if fun.symbol == defn.throwMethod && (settings.allowThrow || isLibraryCode(tree)) => ()
      case Apply(fun, List(seq: SeqLiteral)) if isVarargsWrapper(fun.symbol) =>
        fail("varargs allocate an array and a Seq wrapper", tree.srcPos)
        seq.elems.foreach(traverse)

      // Children first: the reporter drops errors that start inside an already reported span.
      case Apply(fun, args) =>
        val sym = fun.symbol
        val inlined = isInlinedCall(sym)
        traverseCallee(fun)
        args.foreach { arg =>
          arg match
            // Generic Scala Native operations (`p(0) = 1`) box their argument; the
            // box/unbox pair disappears once the operation is inlined.
            case Apply(box, List(value)) if inlined && isBoxing(box.symbol) => traverse(value)
            case _ => traverse(arg)
          if !inlined && sym.exists && !sym.isConstructor then
            checkEscape(arg, if sym.isSetter then "is stored in a field" else s"is passed to '${sym.name}', which is not inlined")
        }
        checkCall(sym, tree)
      case Select(qual, _) if tree.symbol.is(Flags.Method) =>
        traverse(qual)
        checkCall(tree.symbol, tree)

      case vd @ ValDef(_, _, _) =>
        traverse(vd.rhs)
        if isNativeBox(vd.symbol.info) then
          freshLocals(vd.symbol) = vd.symbol.is(Flags.Mutable) || isFresh(vd.rhs)
      case Assign(lhs, rhs) =>
        traverse(rhs)
        if !lhs.symbol.owner.isTerm then checkEscape(rhs, "is stored in a field")
      case Return(expr, from) =>
        traverse(expr)
        if from.symbol == method && returnsNativeBox(method) then checkEscape(expr, "is returned")

      case _ => traverseChildren(tree)

    /** Traverses the receiver of a call without re-checking the called method itself. */
    private def traverseCallee(fun: Tree)(using Context): Unit = fun match
      case Select(qual, _) => traverse(qual)
      case TypeApply(fn, _) => traverseCallee(fn)
      case _: Ident => ()
      case _ => traverse(fun)

    private def checkCall(sym: Symbol, tree: Tree)(using Context): Unit =
      // Constructor calls are reported through their `New`.
      if !sym.exists || sym.isConstructor || sym.hasAnnotation(annotClass) then ()
      else if settings.nativeHeap.matches(sym) then
        if settings.strictHeap then
          fail(s"'${sym.showFullName}' allocates native heap memory (rejected by strict-heap)", tree.srcPos)
      else if isAllowed(sym, tree) then ()
      else if isBoxing(sym) then fail("boxing a primitive allocates on the heap", tree.srcPos)
      else if isReferenceEquality(sym) then
        fail("'==' on references calls equals(), which may allocate; use eq/ne or compare with null", tree.srcPos)
      else if canInfer(sym) then
        infer(sym).foreach { v =>
          fail(s"call to '${sym.showFullName}', which allocates: ${v.msg} (line ${v.pos.sourcePos.line + 1})", tree.srcPos)
        }
      else
        val hint =
          if localDefs.contains(sym) && settings.infer then " (it can be overridden: make it final/private or annotate it)"
          else ""
        fail(
          s"call to '${sym.showFullName}', which is not @zeroAlloc nor allowlisted$hint " +
            s"[allowlist entry: ${Allowlist.entryFor(sym)}]",
          tree.srcPos
        )

    // ── Scala Native boxes ───────────────────────────────────────────

    /** Reports `value` if it is a newly created Ptr/UInt/... that leaves the method. */
    def checkEscape(value: Tree, how: String)(using Context): Unit =
      if isNativeBox(value.tpe) && isFresh(value) then
        val hint =
          if how == "is returned" then "mark the method @alwaysinline or write the result through a Ptr parameter"
          else "mark the callee @alwaysinline or pass a value that already exists (a parameter or a field)"
        fail(
          s"a new '${value.tpe.widen.classSymbol.name}' value $how; Scala Native boxes it on the heap ($hint)",
          value.srcPos
        )

    /** Whether `e` evaluates to a value created in this method, as opposed to an
      * existing box (a parameter, a field, or the result of a call that is not inlined).
      */
    def isFresh(e: Tree)(using Context): Boolean = e match
      case Literal(_) | _: This | _: Super => false
      case Block(_, expr) => isFresh(expr)
      case If(_, thenp, elsep) => isFresh(thenp) || isFresh(elsep)
      case Match(_, cases) => cases.exists(c => isFresh(c.body))
      case Try(body, cases, _) => isFresh(body) || cases.exists(c => isFresh(c.body))
      case Typed(expr, _) => isFresh(expr)
      case Inlined(_, _, expr) => isFresh(expr)
      case TypeApply(Select(expr, _), _) if e.symbol == defn.Any_asInstanceOf => isFresh(expr)
      case Apply(fun, _) => isInlinedCall(fun.symbol)
      case _: Ident | _: Select =>
        val s = e.symbol
        if s.is(Flags.Method) then isInlinedCall(s) && !s.isOneOf(Flags.Accessor | Flags.ParamAccessor)
        else if s.is(Flags.Param) || !s.owner.isTerm then false
        else if s.is(Flags.Mutable) then true
        else freshLocals.getOrElse(s, true)
      case _ => true

  /** Expressions whose value is the result of `tree`. */
  private def tails(tree: Tree)(using Context): List[Tree] = tree match
    case Block(_, expr) => tails(expr)
    case If(_, thenp, elsep) => tails(thenp) ++ tails(elsep)
    case Match(_, cases) => cases.flatMap(c => tails(c.body))
    case Try(body, cases, _) => tails(body) ++ cases.flatMap(c => tails(c.body))
    case Typed(expr, _) => tails(expr)
    case Inlined(_, _, expr) => tails(expr)
    case _ => List(tree)

  /** Ptr, CArray, CStructN, CFuncPtrN, Size and the unsigned types are classes in NIR. */
  private def isNativeBox(tp: Type)(using Context): Boolean =
    val name = tp.widen.classSymbol.fullName.toString
    name == "scala.scalanative.unsafe.Ptr" ||
      name == "scala.scalanative.unsafe.CArray" ||
      name == "scala.scalanative.unsafe.Size" ||
      name == "scala.scalanative.unsafe.CVarArgList" ||
      name.startsWith("scala.scalanative.unsafe.CStruct") ||
      name.startsWith("scala.scalanative.unsafe.CFuncPtr") ||
      Set("UByte", "UShort", "UInt", "ULong", "USize").exists(n => name == s"scala.scalanative.unsigned.$n")

  private def returnsNativeBox(sym: Symbol)(using Context): Boolean =
    !sym.hasAnnotation(alwaysInlineClass) && isNativeBox(sym.info.finalResultType)

  /** Calls whose arguments stay unboxed: they are inlined (Scala Native operations,
    * `@alwaysinline` methods) or compiled to a plain C call (`@extern`).
    */
  private def isInlinedCall(sym: Symbol)(using Context): Boolean =
    sym.exists && (settings.nativeOps.matches(sym) || sym.hasAnnotation(alwaysInlineClass) || isExtern(sym) ||
      ((sym.owner == defn.AnyClass || sym.owner == defn.ObjectClass) && Set("==", "!=", "eq", "ne").contains(sym.name.toString)))

  private def isExtern(sym: Symbol)(using Context): Boolean =
    externClass.exists && (sym.hasAnnotation(externClass) || sym.owner.hasAnnotation(externClass))

  /** Trees that come from the expansion of a library inline method or macro. */
  private def isLibraryCode(tree: Tree)(using Context): Boolean =
    libraryInlines.contains(tree) || (tree.span.exists && !tree.source.file.exists)

  // ── inference ───────────────────────────────────────────────────────

  /** Only methods of the same file are inferred: an incremental build recompiles
    * whole files, so a change to the callee always re-checks its callers. Only
    * effectively final methods, since an override could allocate.
    */
  private def canInfer(sym: Symbol)(using Context): Boolean =
    settings.infer && localDefs.contains(sym) && sym.isEffectivelyFinal

  /** First allocation found in `sym`'s body, if any. Recursion is assumed not to
    * allocate; results that depended on that assumption are only cached at the top.
    */
  private def infer(sym: Symbol)(using Context): Option[Violation] =
    inferred.get(sym) match
      case Some(Inferred.Ok) => None
      case Some(Inferred.Allocates(v)) => Some(v)
      case Some(Inferred.InProgress) =>
        sawCycle = true
        None
      case None =>
        val outerSawCycle = sawCycle
        sawCycle = false
        inferred(sym) = Inferred.InProgress
        inferDepth += 1
        val found = mutable.ListBuffer.empty[Violation]
        try checkMethod(localDefs(sym))(found += _)
        finally inferDepth -= 1
        val result = found.headOption
        result match
          case Some(v) => inferred(sym) = Inferred.Allocates(v)
          case None if !sawCycle || inferDepth == 0 => inferred(sym) = Inferred.Ok
          case None => inferred.remove(sym)
        sawCycle = outerSawCycle || sawCycle
        result

  // ── built-in rules ──────────────────────────────────────────────────

  private def isVarargsWrapper(sym: Symbol)(using Context): Boolean =
    Allowlist.ownerName(sym) == "scala.runtime.ScalaRunTime" && sym.name.toString.startsWith("wrap")

  private def isBoxing(sym: Symbol)(using Context): Boolean =
    val name = sym.name.toString
    (name == "box" && sym.owner.companionClass.isPrimitiveValueClass) ||
      (Allowlist.ownerName(sym) == "scala.runtime.BoxesRunTime" && name.startsWith("boxTo"))

  private def isReferenceEquality(sym: Symbol)(using Context): Boolean =
    (sym.owner == defn.AnyClass || sym.owner == defn.ObjectClass) && (sym.name.toString == "==" || sym.name.toString == "!=")

  private def isNull(tree: Tree): Boolean = tree match
    case Literal(Constant(null)) => true
    case _ => false

  /** Rules that are part of the language rather than of a library; everything else
    * lives in the allowlist files.
    */
  private def isAllowed(sym: Symbol, tree: Tree)(using Context): Boolean =
    val owner = sym.owner
    val name = sym.name.toString
    // `1 + "a"` is a primitive method too, but builds a String.
    def primitiveOp = owner.isPrimitiveValueClass && !Allowlist.paramTypes(sym).contains("java.lang.String")
    def comparesWithNull = tree match
      case Apply(Select(qual, _), List(arg)) => isNull(qual) || isNull(arg)
      case _ => false

    primitiveOp ||
      (name == "unbox" && owner.companionClass.isPrimitiveValueClass) ||
      // Implicit widenings such as `Int.int2long`.
      (owner.companionClass.isPrimitiveValueClass && name.matches("[a-z]+2[a-z]+")) ||
      sym == defn.throwMethod ||
      (sym.isOneOf(Flags.Accessor | Flags.ParamAccessor) && !sym.is(Flags.Lazy)) ||
      ((owner == defn.AnyClass || owner == defn.ObjectClass) &&
        Set("eq", "ne", "isInstanceOf", "asInstanceOf", "getClass", "synchronized").contains(name)) ||
      (isReferenceEquality(sym) && comparesWithNull) ||
      (owner == defn.ArrayClass && Set("apply", "update", "length").contains(name)) ||
      isExtern(sym) ||
      settings.nativeOps.matches(sym) ||
      settings.allowlist.matches(sym)
