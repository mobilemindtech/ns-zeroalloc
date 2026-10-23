package zeroalloc.plugin

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.report

import java.nio.file.{Files, Paths}
import scala.io.Source
import scala.jdk.CollectionConverters.*

/** A set of library methods that are known not to allocate.
  *
  * One entry per line, `#` starts a comment:
  * {{{
  * java.lang.Math.*                    every method of the owner
  * java.lang.String.length             every overload named `length`
  * java.lang.String.indexOf(int)       one overload, by erased parameter types
  * scala.scalanative.unsafe.Tag.materializeCStruct*   every method whose name starts with the prefix
  * !scala.scalanative.unsafe.Ptr.toString             exclusion, wins over any other entry
  * }}}
  * Owners are fully qualified; objects are written without the trailing `$`.
  * Parameter types are erased: primitives in lower case (`int`, `char`, ...),
  * arrays as `elem[]`, everything else fully qualified (`java.lang.Object`).
  */
final class Allowlist private (private val entries: Map[String, List[Allowlist.Entry]]):
  import Allowlist.*

  def matches(sym: Symbol)(using Context): Boolean =
    entries.get(ownerName(sym)).exists { es =>
      val name = sym.name.toString
      lazy val params = paramTypes(sym)
      def hit(e: Entry) = e.matchesName(name) && e.params.forall(_ == params)
      val (excluded, included) = es.partition(_.exclude)
      !excluded.exists(hit) && included.exists(hit)
    }

  def ++(that: Allowlist): Allowlist =
    Allowlist((entries.keySet ++ that.entries.keySet).map { k =>
      k -> (entries.getOrElse(k, Nil) ++ that.entries.getOrElse(k, Nil))
    }.toMap)

object Allowlist:
  private final case class Entry(name: String, params: Option[List[String]], exclude: Boolean):
    def matchesName(n: String): Boolean =
      if name.endsWith("*") then n.startsWith(name.dropRight(1)) else n == name

  val empty: Allowlist = Allowlist(Map.empty)

  def resource(name: String)(using Context): Allowlist =
    val stream = getClass.getResourceAsStream(s"/zeroalloc/allowlist/$name")
    val lines = Source.fromInputStream(stream, "UTF-8").getLines().toList
    parse(lines, s"builtin $name")

  def file(path: String)(using Context): Allowlist =
    val p = Paths.get(path)
    if Files.isReadable(p) then parse(Files.readAllLines(p).asScala.toList, path)
    else
      report.error(s"[zeroalloc] cannot read allowlist file '$path'")
      empty

  def parse(lines: List[String], origin: String)(using Context): Allowlist =
    val parsed = lines.zipWithIndex.flatMap { (raw, i) =>
      val text = raw.takeWhile(_ != '#').trim
      val exclude = text.startsWith("!")
      val line = text.stripPrefix("!").trim
      if line.isEmpty then None
      else
        val (path, params) = line.indexOf('(') match
          case -1 => (line, None)
          case open =>
            val inner = line.substring(open + 1).stripSuffix(")")
            (line.take(open), Some(inner.split(',').map(_.trim).filter(_.nonEmpty).toList))
        val dot = path.lastIndexOf('.')
        if dot <= 0 || dot == path.length - 1 then
          report.warning(s"[zeroalloc] $origin:${i + 1}: malformed allowlist entry '$raw'")
          None
        else Some(path.take(dot) -> Entry(path.drop(dot + 1), params, exclude))
    }
    Allowlist(parsed.groupMap(_._1)(_._2))

  def ownerName(sym: Symbol)(using Context): String =
    sym.owner.fullName.toString.stripSuffix("$")

  /** The allowlist entry that would match exactly this method. */
  def entryFor(sym: Symbol)(using Context): String =
    s"${ownerName(sym)}.${sym.name}(${paramTypes(sym).mkString(",")})"

  /** Erased parameter types, rendered as in the allowlist format. */
  def paramTypes(sym: Symbol)(using Context): List[String] =
    sym.info.paramInfoss.flatten.map(render)

  private def render(tp: Type)(using Context): String = tp match
    case JavaArrayType(elem) => render(elem) + "[]"
    case _ =>
      val cls = tp.classSymbol
      if cls.isPrimitiveValueClass then cls.name.toString.toLowerCase else cls.fullName.toString
