package zeroalloc.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

import scala.collection.mutable

/** Source spans of inline expansions, per file. */
class Regions:
  private val spans = mutable.Map.empty[SourceFile, List[Span]].withDefaultValue(Nil)

  def add(source: SourceFile, span: Span): Unit = spans(source) = span :: spans(source)

  def contains(tree: Tree): Boolean =
    tree.span.exists && spans(tree.source).exists(_.contains(tree.span))

/** `Inlined` nodes only survive until shortly after inlining, so their spans are
  * recorded here for [[ZeroAllocPhase]]: `unsafe` blocks, which are not checked, and
  * expansions of library inline methods, whose `throw`s count as runtime checks.
  */
class CollectInlinesPhase(unsafeBlocks: Regions, libraryInlines: Regions, debug: Boolean) extends PluginPhase:
  val phaseName = "zeroAllocCollectInlines"
  override val runsAfter = Set("inlining")
  override val runsBefore = Set("postInlining")

  override def transformInlined(tree: Inlined)(using Context): Tree =
    if debug then report.echo(s"[zeroalloc] inlined: ${tree.call.symbol.fullName}", tree.srcPos)
    if isUnsafeBlock(tree.call) then unsafeBlocks.add(tree.source, tree.span)
    else if tree.call.symbol.exists && !tree.call.symbol.isDefinedInCurrentRun then
      libraryInlines.add(tree.source, tree.span)
    tree

  /** After inlining, the call of an inline method from another file is reduced to
    * its top-level class, hence `unsafe` living alone in `unsafe.scala`.
    */
  private def isUnsafeBlock(call: Tree)(using Context): Boolean =
    val name = call.symbol.fullName.toString
    name == "zeroalloc.unsafe" || name.startsWith("zeroalloc.unsafe$package")
