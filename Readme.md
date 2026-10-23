# zeroalloc

A Scala 3 compiler plugin for **Scala Native** that rejects, at compile time, any GC heap allocation inside methods annotated with `@zeroAlloc`.

```scala
import zeroalloc.*
import scala.scalanative.unsafe.*

@zeroAlloc
def checksum(buf: Ptr[Byte], len: Int): Int = {
  var i = 0
  var sum = 0
  while (i < len) { sum = sum * 31 + buf(i); i += 1 }   // ✅ pointer access, primitives
  sum
}

@zeroAlloc
def describe(x: Int): String = s"value: $x"            // ❌ boxing / String building
```

```
[error] [zeroalloc] boxing a primitive allocates on the heap
```

In low-latency and systems code every GC allocation is a potential pause. `@zeroAlloc` turns "this method must not allocate" from a code-review convention into something the compiler checks, including allocations that are invisible in the source: boxing, closures, by-name arguments, varargs, captured `var`s and Scala Native's own boxes for `Ptr`, `UInt` and friends.

---

## Installation

The project has two artifacts:

* **`zeroalloc`**, a tiny library with the `@zeroAlloc` annotation and the `unsafe { }` block.
* **`zeroalloc-plugin`**, the compiler plugin that does the checking. It uses the compiler's internal API, so it is published for each full Scala version.

```scala
// sbt (Scala Native project)
libraryDependencies += "br.com.mobilemind" %%% "zeroalloc" % "0.1.0"
addCompilerPlugin("br.com.mobilemind" % "zeroalloc-plugin" % "0.1.0" cross CrossVersion.full)
```

```scala
// scala-cli
//> using platform scala-native
//> using dep br.com.mobilemind::zeroalloc::0.1.0
//> using plugin br.com.mobilemind:::zeroalloc-plugin:0.1.0
```

No `-experimental` flag is needed. Without the plugin, `@zeroAlloc` is an ordinary annotation and checks nothing.

> **Release mode.** The guarantees assume a Scala Native release mode (`releaseFast`, `releaseSize` or `releaseFull`). In debug mode Scala Native does not run its optimizer, and pointer, struct and unsigned operations allocate. See [Scala Native boxes](docs/plugin.md#scala-native-boxes).

---

## What is checked

Inside a `@zeroAlloc` method the plugin rejects:

| Construct | Example |
|---|---|
| Object creation | `new Foo`, `Foo(1)` (case class `apply`), `1 -> 2` |
| Boxing | `val a: Any = 1`, `println(42)`, a generic call with a primitive |
| Closures | lambdas, by-name arguments, SAM conversions, captured `var`s (`IntRef`) |
| Varargs and array literals | `f(1, 2, 3)` with `xs: Int*` |
| String building | `s"..."`, `"a" + x` |
| A new `Ptr`/`UInt`/`CSize`/... leaving the method | `g(p + 1)`, `return p + 1`, `field = p + 1` |
| Calls to code not known to be allocation-free | anything not covered by the rules below |
| `==` on references | `a == b` calls `equals`; use `eq`/`ne`, or compare with `null` |

A call is accepted when the callee is:

* another `@zeroAlloc` method,
* a primitive operation, array access, `eq`/`ne`, `isInstanceOf`/`asInstanceOf`,
* a Scala Native operation (`Ptr`, `CStruct`, `CArray`, unsigned types, `stackalloc`, `sizeof`) or an `@extern` C function,
* a library method in the [built-in allowlist](docs/allowlist.md) (`Math`, `Integer.bitCount`, `String.length`, atomics, `ByteBuffer.getInt`, ...),
* a private or final method **of the same file** whose body passes the same checks (inferred, no annotation needed).

Overrides of a `@zeroAlloc` method must be annotated too, since a virtual call can reach them.

## Escape hatch

```scala
@zeroAlloc
def process(x: Int): Int = {
  zeroalloc.unsafe {
    println(s"debug: $x")   // not checked
  }
  x * 2                     // checked
}
```

## Options

Passed as `scalacOptions += "-P:zeroalloc:<option>"`:

| Option | Effect |
|---|---|
| `allow-throw` | Accept allocations in `throw` expressions and in methods that only allocate when they throw (`require(cond)`, `Math.addExact`, ...). |
| `strict-heap` | Also reject native heap allocation (`malloc`, `calloc`, `realloc`, ...). By default only GC allocations are rejected. |
| `no-infer` | Do not infer same-file helpers: every callee must be `@zeroAlloc` or allowlisted. |
| `allowlist:<path>` | Add the entries of an allowlist file ([format](docs/allowlist.md#file-format)). Can be repeated. |
| `allow:<Owner>` | Accept every method of a class or object, e.g. `allow:com.acme.FastMath`. |
| `debug` | Print the inline expansions the plugin sees. |

---

## Documentation

* [docs/plugin.md](docs/plugin.md): how the plugin works, every rule in detail, Scala Native boxing, inference, limitations.
* [docs/allowlist.md](docs/allowlist.md): the allowlist format, what is in the built-in list and what was left out and why, and how to verify it against the generated IR.

## Development

```bash
sbt plugin/testFull        # plugin tests: compile snippets with Scala Native + the plugin
sbt tests/test             # runtime tests on Scala Native, compiled with the plugin
sbt conformance/nativeLink && \
  python3 scripts/ir-allocs.py target/out/native0.5/scala-3.8.4/zeroalloc-conformance/native/generated '_SM\d+Conformance\$D(?!4main)'
                           # checks in the optimized IR that accepted methods really do not allocate
```

## License

Apache 2.0, see [LICENSE](LICENSE).
