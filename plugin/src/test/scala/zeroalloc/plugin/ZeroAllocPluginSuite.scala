package zeroalloc.plugin

import dotty.tools.dotc.Driver
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.nio.file.Files
import scala.collection.mutable.ListBuffer

class ZeroAllocPluginSuite extends munit.FunSuite {

  // Set by build.sbt: the Scala Native classpath and the nscplugin + zeroalloc plugin jars.
  private val classpath = sys.props("zeroalloc.test.classpath")
  private val plugins = sys.props("zeroalloc.test.plugins").split(',').toList

  /** Compiles `code` like a Scala Native build would, and returns the error messages. */
  private def compileWithPlugin(code: String, options: String*): List[String] = {
    val dir = Files.createTempDirectory("zeroalloc-test")
    val src = dir.resolve("Probe.scala")
    Files.writeString(src, s"package probe\nimport zeroalloc.*\n$code\n")

    val errors = ListBuffer.empty[String]
    val reporter = new SimpleReporter {
      def report(d: Diagnostic): Unit = if (d.level == Diagnostic.ERROR) errors += d.message
    }
    val args = Array("-classpath", classpath, "-d", dir.toString, "-color:never", "-Xplugin-require:zeroalloc") ++
      plugins.map(p => s"-Xplugin:$p") ++
      options.map(o => s"-P:zeroalloc:$o") :+
      src.toString
    new Driver().process(args, reporter, null)
    errors.toList
  }

  private def assertRejected(code: String, expected: String, options: String*)(implicit loc: munit.Location): Unit = {
    val errors = compileWithPlugin(code, options*)
    assert(errors.exists(_.contains(expected)), s"expected an error containing '$expected', got:\n${errors.mkString("\n")}")
  }

  private def assertAccepted(code: String, options: String*)(implicit loc: munit.Location): Unit =
    assertEquals(compileWithPlugin(code, options*), Nil)

  // ── allocations ───────────────────────────────────────────────────

  test("new") {
    assertRejected("class P\nobject t { @zeroAlloc def f(): Unit = { new P } }", "'new ")
  }
  test("case class apply") {
    // The result must be used: an unused `U(1)` is pure and gets dropped by the compiler.
    assertRejected("case class U(n: Int)\nobject t { @zeroAlloc def f(): U = U(1) }", "'probe.U.apply'")
  }
  test("string interpolation") {
    assertRejected("object t { @zeroAlloc def f(x: String): String = s\"v: $x\" }", "[zeroalloc]")
  }
  test("string concatenation with a primitive on the left") {
    assertRejected("object t { @zeroAlloc def f(x: Int): String = x + \"!\" }", "[zeroalloc]")
  }
  test("println") {
    assertRejected("object t { @zeroAlloc def f(): Unit = println(42) }", "boxing")
  }
  test("tuple via ->") {
    assertRejected("object t { @zeroAlloc def f(): (Int, Int) = 1 -> 2 }", "boxing")
  }
  test("range via to") {
    assertRejected("object t { @zeroAlloc def f(): Int = (1 to 10).length }", "to$extension")
  }
  test("varargs") {
    assertRejected("object t { @zeroAlloc def v(xs: Int*): Int = 0\n @zeroAlloc def f(): Unit = { v(1, 2) } }", "varargs")
  }
  test("boxing") {
    assertRejected("object t { @zeroAlloc def f(n: Int): Any = n }", "boxing")
  }
  test("by-name argument") {
    assertRejected("object t { @zeroAlloc def bn(x: => Int): Int = x\n @zeroAlloc def f(): Int = bn(3) }", "closure")
  }
  test("lambda") {
    assertRejected("object t { @zeroAlloc def f(): Int => Int = (x: Int) => x + 1 }", "closure")
  }
  test("captured var") {
    assertRejected("object t { @zeroAlloc def f(): Unit = { var c = 0; val g = () => c += 1 } }", "IntRef")
  }
  test("override of @zeroAlloc method without annotation") {
    assertRejected("trait A { @zeroAlloc def f(): Int }\nclass B extends A { def f(): Int = 1 }", "must be annotated")
  }

  // ── built-in rules ────────────────────────────────────────────────

  test("primitive arithmetic on every primitive type") {
    assertAccepted(
      """object t {
        |  @zeroAlloc def i(a: Int): Int = a * 2 + 1
        |  @zeroAlloc def l(a: Long): Long = (a + 1L) >>> 2
        |  @zeroAlloc def d(a: Double): Double = a * 2.0
        |  @zeroAlloc def c(a: Char): Int = a - '0'
        |  @zeroAlloc def b(a: Boolean): Boolean = !a && true
        |}""".stripMargin
    )
  }
  test("array access") {
    assertAccepted("object t { @zeroAlloc def f(a: Array[Int]): Int = { a(0) = 1; a(0) + a.length } }")
  }
  test("recursion and calls to @zeroAlloc methods") {
    assertAccepted("object t { @zeroAlloc def f(n: Int): Int = if (n <= 0) 0 else f(n - 1)\n @zeroAlloc def g(): Int = f(3) }")
  }
  test("while loop with local vars") {
    assertAccepted("object t { @zeroAlloc def f(n: Int): Int = { var i = 0; var s = 0; while (i < n) { s += i; i += 1 }; s } }")
  }
  test("allocations inside unsafe") {
    assertAccepted("class P\nobject t { @zeroAlloc def f(x: Int): Int = { zeroalloc.unsafe { new P; println(s\"$x\") }; x } }")
  }
  test("non-annotated methods are not checked") {
    assertAccepted("class P\nobject t { def f(): P = new P }")
  }
  test("== on references is rejected") {
    assertRejected("object t { @zeroAlloc def f(a: AnyRef, b: AnyRef): Boolean = a == b }", "use eq/ne")
  }
  test("eq/ne and comparisons with null are accepted") {
    assertAccepted("object t { @zeroAlloc def f(a: AnyRef, b: AnyRef): Boolean = (a eq b) || a == null || null != b }")
  }

  // ── allow-throw ───────────────────────────────────────────────────

  private val throwing =
    "object t { @zeroAlloc def f(x: Int): Int = { if (x < 0) throw new IllegalArgumentException(s\"bad: $x\"); x } }"

  test("throw allocates by default") {
    assertRejected(throwing, "'new ")
  }
  test("allow-throw accepts allocations in the thrown expression") {
    assertAccepted(throwing, "allow-throw")
  }
  test("allow-throw accepts require(cond)") {
    assertAccepted("object t { @zeroAlloc def f(x: Int): Int = { require(x > 0); x } }", "allow-throw")
  }
  test("allow-throw still rejects require(cond, msg)") {
    assertRejected("object t { @zeroAlloc def f(x: Int): Int = { require(x > 0, \"positive\"); x } }", "closure", "allow-throw")
  }

  // ── inference ─────────────────────────────────────────────────────

  test("private helper that does not allocate is inferred") {
    assertAccepted("object t { private def sq(x: Int): Int = x * x\n @zeroAlloc def f(x: Int): Int = sq(x) + 1 }")
  }
  test("helper that allocates is reported with the reason") {
    assertRejected(
      "class P\nobject t { private def mk(): P = new P\n @zeroAlloc def f(): P = mk() }",
      "which allocates: heap allocation: 'new probe.P'"
    )
  }
  test("parameterless helper that allocates") {
    assertRejected("class P\nobject t { def mk: P = new P\n @zeroAlloc def f(): P = mk }", "which allocates")
  }
  test("mutually recursive helpers") {
    assertAccepted(
      """object t {
        |  def even(n: Int): Boolean = if (n == 0) true else odd(n - 1)
        |  def odd(n: Int): Boolean = if (n == 0) false else even(n - 1)
        |  @zeroAlloc def f(n: Int): Boolean = even(n)
        |}""".stripMargin
    )
  }
  test("allocation inside a recursive cycle is found") {
    assertRejected(
      """class P
        |object t {
        |  def a(n: Int): Int = if (n == 0) 0 else b(n - 1)
        |  def b(n: Int): Int = { new P; a(n) }
        |  @zeroAlloc def f(n: Int): Int = a(n)
        |}""".stripMargin,
      "which allocates"
    )
  }
  test("overridable methods are not inferred") {
    assertRejected("class C { def g(x: Int): Int = x }\nobject t { @zeroAlloc def f(c: C): Int = c.g(1) }", "can be overridden")
  }
  test("no-infer requires annotations") {
    assertRejected("object t { private def sq(x: Int): Int = x * x\n @zeroAlloc def f(x: Int): Int = sq(x) }", "not @zeroAlloc", "no-infer")
  }

  // ── allowlist files ───────────────────────────────────────────────

  private val overloads =
    "object Lib { def g(x: Int): Int = x; def g(x: String): Int = 0 }\n" +
      "object t { @zeroAlloc def a(): Int = Lib.g(1)\n @zeroAlloc def b(): Int = Lib.g(\"s\") }"

  test("allowlist entries can select one overload") {
    val file = Files.createTempFile("allowlist", ".txt")
    Files.writeString(file, "# comment\nprobe.Lib.g(int)\n")
    val errors = compileWithPlugin(overloads, s"allowlist:$file", "no-infer")
    assertEquals(errors.size, 1, errors.mkString("\n"))
    assert(errors.head.contains("probe.Lib.g"), errors.head)
  }
  test("allow:<owner> allows every method of the owner") {
    assertAccepted(overloads, "allow:probe.Lib", "no-infer")
  }

  // ── Scala Native ──────────────────────────────────────────────────

  private val sn =
    "import scala.scalanative.unsafe.*\nimport scala.scalanative.unsigned.*\n" +
      "import scala.scalanative.annotation.alwaysinline\nimport scala.scalanative.libc\n"

  test("Ptr, stackalloc and sizeof") {
    assertAccepted(sn + "object t { @zeroAlloc def f(n: Int): Long = { val p = stackalloc[Int](4); val q = stackalloc[Byte](n); p(0) = 1; !p + p(1) + !(p + 1) + sizeof[Long].toLong + q.toLong } }")
  }
  test("CStruct and CArray fields") {
    assertAccepted(sn + "object t { @zeroAlloc def f(p: Ptr[CStruct2[Int, Long]], a: Ptr[CArray[Int, Nat._8]]): Long = { p._1 = 3; val arr = !a; arr(1) = 2; p._2 + p._1 + arr(1) + arr.length } }")
  }
  test("unsigned arithmetic") {
    assertAccepted(sn + "object t { @zeroAlloc def f(a: UInt, b: CSize): Long = (a + 2.toUInt * a).toLong + (b + 1.toUSize).toLong + (if (a < 3.toUInt) 1 else 0) }")
  }
  test("@extern calls") {
    assertAccepted(sn + "object t { @zeroAlloc def f(p: Ptr[Byte], n: CSize): Unit = { libc.string.memset(p + 1, 0, n) } }")
  }
  test("native heap is accepted by default and rejected by strict-heap") {
    val code = sn + "object t { @zeroAlloc def f(n: CSize): Unit = { val p = libc.stdlib.malloc(n); libc.stdlib.free(p) } }"
    assertAccepted(code)
    assertRejected(code, "strict-heap", "strict-heap")
  }
  test("library methods that allocate") {
    assertRejected(sn + "object t { @zeroAlloc def f(p: CString): String = fromCString(p) }", "fromCString")
    assertRejected(sn + "object t { @zeroAlloc def f(p: Ptr[Int]): String = p.toString }", "toString")
  }

  test("a new Ptr passed to a method that is not inlined is boxed") {
    assertRejected(sn + "object t { @zeroAlloc def g(p: Ptr[Int]): Int = !p\n @zeroAlloc def f(p: Ptr[Int]): Int = g(p + 1) }", "is passed to 'g'")
  }
  test("a new Ptr held in a local val is still new") {
    assertRejected(sn + "object t { @zeroAlloc def g(p: Ptr[Int]): Int = !p\n @zeroAlloc def f(p: Ptr[Int]): Int = { val q = p + 1; g(q) } }", "is passed to 'g'")
  }
  test("an existing Ptr can be passed on") {
    assertAccepted(sn + "object t { @zeroAlloc def g(p: Ptr[Int]): Int = !p\n @zeroAlloc def h(p: Ptr[Int]): Ptr[Int] = p\n @zeroAlloc def f(p: Ptr[Int]): Int = g(p) + g(h(p)) }")
  }
  test("a new Ptr can be passed to an @alwaysinline method") {
    assertAccepted(sn + "object t { @alwaysinline @zeroAlloc def g(p: Ptr[Int]): Int = !p\n @zeroAlloc def f(p: Ptr[Int]): Int = g(p + 1) }")
  }
  test("returning a new Ptr or UInt is boxed") {
    assertRejected(sn + "object t { @zeroAlloc def f(p: Ptr[Int]): Ptr[Int] = p + 1 }", "is returned")
    assertRejected(sn + "object t { @zeroAlloc def f(a: Int): UInt = a.toUInt }", "is returned")
    assertRejected(sn + "object t { @zeroAlloc def f(n: CSize): Ptr[Byte] = libc.stdlib.malloc(n) }", "is returned")
  }
  test("returning an existing Ptr, or from an @alwaysinline method") {
    assertAccepted(sn + "object t { @zeroAlloc def f(p: Ptr[Int], c: Boolean): Ptr[Int] = if (c) p else null\n @alwaysinline @zeroAlloc def g(p: Ptr[Int]): Ptr[Int] = p + 1 }")
  }
  test("storing a new Ptr in a field is boxed") {
    assertRejected(sn + "object t { var cur: Ptr[Int] = null\n @zeroAlloc def f(p: Ptr[Int]): Unit = { cur = p + 1 } }", "is stored in a field")
  }

  // ── built-in allowlist ────────────────────────────────────────────

  test("allowlisted library methods") {
    assertAccepted(
      """import java.util.concurrent.atomic.AtomicInteger
        |object t {
        |  @zeroAlloc def f(a: AtomicInteger, s: String, d: Double): Long =
        |    a.incrementAndGet() + s.indexOf('x'.toInt) + s.length + Math.max(d, 1.0).toLong +
        |      java.lang.Long.numberOfTrailingZeros(System.nanoTime()) + (if (s.equals("a")) 1 else 0)
        |}""".stripMargin
    )
  }
  test("allowlist entries are per overload") {
    assertRejected("object t { @zeroAlloc def f(s: String): Int = s.indexOf(\"x\") }", "java.lang.String.indexOf(java.lang.String)")
  }

  // ── documentation examples ────────────────────────────────────────

  test("README example") {
    val checksum =
      """@zeroAlloc
        |def checksum(buf: Ptr[Byte], len: Int): Int = {
        |  var i = 0
        |  var sum = 0
        |  while (i < len) { sum = sum * 31 + buf(i); i += 1 }
        |  sum
        |}""".stripMargin
    assertAccepted(sn + checksum)
    assertRejected(sn + "@zeroAlloc\ndef describe(x: Int): String = s\"value: $x\"", "boxing")
  }
  test("allow-throw accepts assert(cond)") {
    assertAccepted("object t { @zeroAlloc def f(x: Int): Int = { assert(x > 0); x } }", "allow-throw")
  }
  test("lazy local val") {
    assertRejected("object t { @zeroAlloc def f(x: Int): Int = { lazy val y = x * 2; y } }", "'new ")
  }
}
