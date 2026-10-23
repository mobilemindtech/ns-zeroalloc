package zeroalloc.plugin

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report

/** Compiler plugin that rejects GC heap allocations inside `@zeroAlloc` methods.
  * See `docs/plugin.md` for the rules and options.
  */
class ZeroAllocPlugin extends StandardPlugin:
  val name = "zeroalloc"
  override val description = "rejects GC heap allocations inside @zeroAlloc methods"

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    val settings = Settings.parse(options)
    val unsafeBlocks = Regions()
    val libraryInlines = Regions()
    List(
      CollectInlinesPhase(unsafeBlocks, libraryInlines, settings.debug),
      ZeroAllocPhase(unsafeBlocks, libraryInlines, settings)
    )

/** Options passed as `-P:zeroalloc:<option>`. */
final case class Settings(
  allowlist: Allowlist,
  nativeOps: Allowlist,
  nativeHeap: Allowlist,
  allowThrow: Boolean,
  strictHeap: Boolean,
  infer: Boolean,
  debug: Boolean
)

object Settings:
  def parse(options: List[String])(using Context): Settings =
    val known = Set("allow-throw", "strict-heap", "no-infer", "debug")
    options.filterNot(o => known(o) || o.startsWith("allow:") || o.startsWith("allowlist:")).foreach { o =>
      report.warning(s"[zeroalloc] unknown option '$o'")
    }
    val allowThrow = options.contains("allow-throw")

    val builtin = Allowlist.resource("core.txt") ++
      (if allowThrow then Allowlist.resource("throw.txt") else Allowlist.empty)
    val fromOwners = Allowlist.parse(
      options.collect { case s"allow:$owner" => s"$owner.*" },
      "-P:zeroalloc:allow"
    )
    val fromFiles = options.collect { case s"allowlist:$path" => Allowlist.file(path) }

    Settings(
      allowlist = fromFiles.foldLeft(builtin ++ fromOwners)(_ ++ _),
      nativeOps = Allowlist.resource("native-ops.txt"),
      nativeHeap = Allowlist.resource("native-heap.txt"),
      allowThrow = allowThrow,
      strictHeap = options.contains("strict-heap"),
      infer = !options.contains("no-infer"),
      debug = options.contains("debug")
    )
