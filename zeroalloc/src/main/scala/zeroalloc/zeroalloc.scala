package zeroalloc

import scala.annotation.{MacroAnnotation, experimental}
import scala.quoted.*

inline def unsafe[T](inline f: => T): T = f

@experimental
class zeroAlloc extends MacroAnnotation {
  def transform(using Quotes)(
    tree: quotes.reflect.Definition,
    companion: Option[quotes.reflect.Definition]
  ): List[quotes.reflect.Definition] = {
    import quotes.reflect.*

    tree match {
      case DefDef(name, paramss, tpt, Some(rhs)) => {
        val validatedRhs = '{ macros.validate(${ rhs.asExpr }) }.asTerm
        val newDef = DefDef.copy(tree)(name, paramss, tpt, Some(validatedRhs))
        List(newDef)
      }
      case _ => {
        report.errorAndAbort("@zeroAlloc só pode ser aplicado em definições de métodos (def).", tree.pos)
        List(tree)
      }
    }
  }
}

object macros {

  inline def validate[T](inline body: T): T = ${ validateImpl('body) }

  private def validateImpl[T: Type](expr: Expr[T])(using qctx: Quotes): Expr[T] = {
    import qctx.reflect.*

    val zeroAllocAnnotSymbol = TypeRepr.of[zeroAlloc].typeSymbol

    val traverser = new TreeTraverser {
      override def traverseTree(tree: Tree)(owner: Symbol): Unit = {
        tree match {
          // 🛡️ REGRA 0: Captura blocos inlinados (como o 'unsafe') e pula seus filhos
          case Inlined(callOpt, bindings, expansion) => {
            val isUnsafe = callOpt.map(_.symbol).exists(isUnsafeBlock)

            if (isUnsafe) {
              ()
            } else {
              super.traverseTree(tree)(owner)
            }
          }

          // 🛑 REGRA 1: Bloqueia 'new' (Instanciações diretas na Heap)
          case New(tpt) => {
            report.errorAndAbort(
              s"[ZeroAlloc] Alocação na Heap detectada com 'new ${tpt.show}'.",
              tree.pos
            )
          }

          // 🔍 REGRA 2 & 3: Valida chamadas de método e interpolação de String
          case Apply(fun, args) => {
            val sym = fun.symbol

            // 🛑 Interpolação de String (s"...", f"...", raw"...")
            if (isStringInterpolation(sym)) {
              report.errorAndAbort(
                "[ZeroAlloc] Interpolação de String aloca na Heap.",
                tree.pos
              )
            }
            else if (isUnsafeBlock(sym)) {
              ()
            }
            else if (isCompilerInternal(sym)) {
              super.traverseTree(tree)(owner)
            }
            else if (sym.hasAnnotation(zeroAllocAnnotSymbol) || isAllowedIntrinsic(sym)) {
              super.traverseTree(tree)(owner)
            }
            else {
              report.errorAndAbort(
                s"[ZeroAlloc] Chamada insegura para o método '${sym.name}'.",
                tree.pos
              )
            }
          }

          case _ => {
            super.traverseTree(tree)(owner)
          }
        }
      }
    }

    traverser.traverseTree(expr.asTerm)(Symbol.spliceOwner)
    expr
  }

  // Verifica se o símbolo pertence à classe StringContext (s"...", f"...", etc.)
  private def isStringInterpolation(using qctx: Quotes)(sym: qctx.reflect.Symbol): Boolean = {
    sym.exists && sym.owner.fullName == "scala.StringContext"
  }

  private def isUnsafeBlock(using qctx: Quotes)(sym: qctx.reflect.Symbol): Boolean = {
    sym.exists && (
      sym.name == "unsafe" && (
        sym.fullName == "zeroalloc.unsafe" ||
          sym.owner.fullName.startsWith("zeroalloc")
        )
      )
  }

  private def isCompilerInternal(using qctx: Quotes)(sym: qctx.reflect.Symbol): Boolean = {
    import qctx.reflect.*
    val ownerName = sym.owner.fullName

    sym.name == "<init>" ||
      ownerName.startsWith("scala.quoted") ||
      ownerName.startsWith("scala.runtime") ||
      (sym.flags.is(Flags.Synthetic) && ownerName.startsWith("scala."))
  }

  private def isAllowedIntrinsic(using qctx: Quotes)(sym: qctx.reflect.Symbol): Boolean = {
    import qctx.reflect.*
    val ownerSym = sym.owner
    ownerSym.fullName.startsWith("scala.scalanative.unsafe") ||
      ownerSym == TypeRepr.of[Int].typeSymbol ||
      ownerSym == TypeRepr.of[Boolean].typeSymbol
  }
}