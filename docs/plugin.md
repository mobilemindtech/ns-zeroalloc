# How the zeroalloc plugin works

This document describes what the plugin checks and why. It targets Scala Native 0.5 with Scala 3.8; the rules were verified against the LLVM IR that Scala Native 0.5.12 generates in release mode.

* [Phases](#phases)
* [What is an allocation](#what-is-an-allocation)
* [Calls](#calls)
* [Scala Native boxes](#scala-native-boxes)
* [Inference of same-file helpers](#inference-of-same-file-helpers)
* [Exceptions](#exceptions)
* [Native heap](#native-heap)
* [The `unsafe` block](#the-unsafe-block)
* [Overrides](#overrides)
* [Limitations](#limitations)
* [Error messages](#error-messages)

## Phases

The plugin adds two phases to the compiler.

1. **`zeroAllocCollectInlines`** runs right after `inlining`, while the trees still record which inline method each expansion came from. It records the source spans of:
   * `zeroalloc.unsafe { ... }` blocks, which are not checked;
   * expansions of inline methods and macros from a library (anything not compiled in the current run), whose `throw`s are treated as runtime checks (see [Exceptions](#exceptions)).

2. **`zeroAllocCheck`** runs after `lambdaLift` and checks the body of every `@zeroAlloc` method. Running this late is the point of the plugin: by then the compiler has turned the implicit allocations into explicit trees.
   * Boxing is an explicit `Int.box(x)` call.
   * Closures, including by-name arguments and SAM conversions, are `Closure` nodes.
   * Captured `var`s became `IntRef.create(...)`.
   * Varargs became an array literal plus `ScalaRunTime.wrapIntArray`.
   * A parameterless call `foo` became `foo()`.

The annotation itself (`zeroalloc.zeroAlloc`) is a plain `StaticAnnotation`. Without the plugin it does nothing.

## What is an allocation

The plugin rejects these trees inside a `@zeroAlloc` method:

| Tree | Comes from |
|---|---|
| `New` | `new C`, case class `apply` bodies, `IntRef`/`ObjectRef` for captured `var`s, lazy local vals |
| `Closure` | lambdas, by-name arguments, SAM conversions, `for` comprehensions |
| `SeqLiteral` | array literals, varargs |
| `X.box(...)`, `BoxesRunTime.boxToX(...)` | a primitive used as `Any`/`AnyRef` or as a generic type argument |
| a call to a method that is not known to be allocation-free | see [Calls](#calls) |
| a new Scala Native box leaving the method | see [Scala Native boxes](#scala-native-boxes) |

"Allocation" means a **GC heap** allocation. Stack memory (`stackalloc`) is fine. Native heap memory (`malloc`) is accepted unless `strict-heap` is on.

Errors are reported innermost first. The Scala compiler drops an error whose position starts inside one it already reported, so the most specific reason is the one you see.

## Calls

A call is accepted if one of these holds:

1. **The callee is `@zeroAlloc`.** It is checked where it is defined.
2. **Built-in language rules:**
   * methods of the primitive classes (`+`, `<`, `toLong`, ...), except the ones that take a `String` (`1 + "a"` builds a string);
   * implicit widenings (`Int.int2long`) and unboxing;
   * `throw`;
   * non-lazy field accessors and setters;
   * `eq`, `ne`, `isInstanceOf`, `asInstanceOf`, `getClass`, `synchronized`;
   * `==`/`!=` when one side is `null`;
   * `Array#apply`, `update` and `length`.
3. **`@extern` functions** (C calls). They do not touch the GC heap.
4. **Scala Native operations** (`native-ops.txt`): `Ptr`, `CArray`, `CStructN`, `Size`, the unsigned types, `Tag` materializers, `Intrinsics`, and the `unsafe`/`unsigned` package helpers. See [Scala Native boxes](#scala-native-boxes).
5. **The allowlist** (`core.txt`, plus `throw.txt` with `allow-throw`, plus your own files). See [allowlist.md](allowlist.md).
6. **Inference:** an effectively final method with a body in the same file that passes the same checks. See [below](#inference-of-same-file-helpers).

Anything else is rejected. The message includes the exact allowlist entry that would accept the method:

```
[zeroalloc] call to 'String.indexOf', which is not @zeroAlloc nor allowlisted
  [allowlist entry: java.lang.String.indexOf(java.lang.String)]
```

**`==` on references** gets its own message. It compiles to a call to `equals`, which is virtual and may allocate. Use `eq`/`ne` for identity, or compare with `null`.

## Scala Native boxes

This is the part of the rules that is specific to Scala Native, and it is easy to get wrong without looking at the generated code.

In NIR, `Ptr[T]`, `CArray`, `CStructN`, `CFuncPtrN`, `Size`, `UByte`, `UShort`, `UInt`, `ULong` and `USize` are **heap classes**, not primitives. In the library source, `p + 1`, `p._1` and `a.toUInt` all create new objects, and generic accesses such as `p(0) = v` box the primitive through `Tag[T]`.

Scala Native's optimizer (Interflow, release modes only) inlines these operations and removes the boxes. **But it can only remove a box that does not leave the method.** These cases were measured in the optimized IR:

| Code | Allocates? |
|---|---|
| `p(0) = v; !p + p(1)`, `p._1 = v`, `a(1) = 3` on a `CArray` | no |
| `a + 2.toUInt * a`, `b + 1.toUSize` (local) | no |
| `stackalloc[Int](n)`, `sizeof[T]` | no |
| `g(p)`, where `p` is a parameter | no (already a box) |
| `g(h(p))`, where `h` is a call that is not inlined | no (`h` returned a box) |
| `inl(p + 1)`, where `inl` is `@alwaysinline` | no |
| `libc.string.memset(p + 1, 0, n)` (`@extern`) | no |
| **`g(p + 1)`, where `g` is not inlined** | **yes, `new Ptr`** |
| **`val q = p + 1; g(q)`** | **yes, `new Ptr`** |
| **`g(!pp)`, a pointer loaded from memory** | **yes, `new Ptr`** |
| **`def f(p: Ptr[Int]): Ptr[Int] = p + 1`** | **yes, `new Ptr`** |
| **`def f(a: Int): UInt = a.toUInt`** | **yes, `new UInt`** |
| **`def f(n: CSize): Ptr[Byte] = malloc(n)`** | **yes, `new Ptr`** |

So the plugin applies two rules.

1. **Scala Native operations are accepted.** Primitive arguments boxed only to be passed to them (`p(0) = 1` → `update(0, Int.box(1))`) are not reported, because the box/unbox pair disappears once the operation is inlined.

2. **A new box must not escape.** A value of one of the types above is *new* if it was created in this method: the result of a Scala Native operation, an `@extern` call or an `@alwaysinline` method, or a local `val` holding such a value. It is *not new* if it is a parameter, a field, `null`, or the result of a call that is not inlined. A new value is reported when it:
   * is passed to a method that is not inlined (not a Scala Native operation, not `@alwaysinline`, not `@extern`);
   * is returned, unless the method itself is `@alwaysinline`;
   * is stored in a field.

   The fixes the message suggests are:
   * mark the callee `@alwaysinline`;
   * pass a value that already exists;
   * write the result through a `Ptr` parameter instead of returning it.

**Debug mode.** Without Interflow, every one of these operations allocates: `Tag` instances, boxed struct fields, every unsigned arithmetic result. The plugin's guarantees only hold in release modes. A build in debug mode compiles fine but allocates.

## Inference of same-file helpers

Annotating every helper is tedious, so a call to a method that is not annotated is accepted when all of these hold:

* the method has a body **in the same source file**;
* it is **effectively final**: private, final, a member of an `object` or of a final class, or local;
* its body passes the same checks, recursively. Recursion, including mutual recursion, is assumed not to allocate.

Only same-file methods are inferred because of incremental compilation. sbt recompiles whole files, so when a helper changes, every caller that relied on inference is in the same file and is re-checked. If inference crossed files, changing a helper could make an unchanged caller allocate without recompiling it.

Only effectively final methods are inferred because a virtual call can dispatch to an override that allocates. For a method that can be overridden, the error says so.

When an inferred helper allocates, the error points at the call and gives the helper's first allocation:

```
[zeroalloc] call to 'probe.t.mk', which allocates: heap allocation: 'new probe.P' (line 4)
```

`-P:zeroalloc:no-infer` turns inference off.

## Exceptions

By default, the user's own `throw new X(...)` is an allocation and is rejected.

* **`allow-throw`** accepts the expression of every `throw`, which is usually what low-latency code wants: the failure path may allocate, the normal path may not. It also enables `throw.txt`: methods that only allocate when they throw, such as `require(cond)`, `???`, `assert` and `Math.addExact`. Note that `require(cond, msg)` is still rejected, because its message is a by-name argument (a closure on every call).
* **Library checks are always accepted.** A `throw` that comes from the expansion of a library inline method or macro (for example the negative-size check that `stackalloc[T](n)` generates) is treated like a bounds check.
* **Allowlisted methods may throw.** Their exceptions for invalid arguments, such as an index out of bounds in `String.charAt`, are accepted like those of `a(i)` and `/`.

## Native heap

Memory from `malloc` and similar functions is not managed by the GC, so it is accepted by default. With **`strict-heap`**, the functions in `native-heap.txt` are rejected:

* `libc.stdlib`: `malloc`, `calloc`, `realloc`, `aligned_alloc`;
* `libc.stdio`: `fopen`, `tmpfile`;
* `runtime.ffi`: `malloc`, `calloc`, `realloc`.

`Zone` allocation is **not** accepted in either mode, because `MemoryPoolZone` allocates GC objects (pages, offsets). Use `stackalloc` or `malloc`/`free`.

## The `unsafe` block

`zeroalloc.unsafe { ... }` is an inline method that returns its argument. The collect phase records the span of every expansion, and the check phase skips trees inside it. `unsafe` lives alone in `unsafe.scala` because, after inlining, the compiler only records the top-level class of an inline call (`zeroalloc.unsafe$package`).

## Overrides

A method that overrides a `@zeroAlloc` method must be `@zeroAlloc` too:

```
[zeroalloc] 'f' overrides a @zeroAlloc method and must be annotated with @zeroAlloc too
```

An abstract `@zeroAlloc` method is a contract for its implementations.

## Limitations

* **Release mode is assumed.** See [Scala Native boxes](#scala-native-boxes).
* **The allowlist is a list of facts about a library version.** It was checked against Scala Native 0.5.12. Run the [conformance check](allowlist.md#verifying-the-allowlist) when upgrading.
* **Interflow decides what to inline.** The plugin relies on `@alwaysinline` and on Scala Native operations being inlined. Interflow may also inline more than that, so the plugin is conservative: it can reject code that does not allocate in practice, such as passing a new `Ptr` to a small method that Interflow inlines anyway. Mark such methods `@alwaysinline`.
* **Virtual calls through allowlisted types.** An allowlisted method called on a subclass instance runs the subclass's override, which is not checked. Most of the built-in list is statics, objects and final classes (`String`, `Ptr`, the unsigned types). The exceptions are the atomics and `ByteBuffer`/`Buffer`, which are not final. In Scala Native's javalib `HeapByteBuffer` is the only buffer implementation, but nothing stops your own subclass of `AtomicInteger`.
* **Lazy initialization of objects.** The first access to an `object` runs its initializer, which may allocate once. The plugin does not report module accesses.
* **Exceptions thrown by the runtime** (null pointer, out of bounds, division by zero, class cast) allocate the exception. They are not reported.

## Error messages

| Message | Meaning |
|---|---|
| `heap allocation: 'new X'` | object creation |
| `closure allocates a Function object ...` | lambda, by-name argument or SAM |
| `array/varargs literal allocates on the heap` | `Array(1, 2)`, varargs |
| `varargs allocate an array and a Seq wrapper` | call with varargs |
| `boxing a primitive allocates on the heap` | primitive used as `Any`/generic |
| `'==' on references calls equals() ...` | use `eq`/`ne` or compare with `null` |
| `call to 'X', which is not @zeroAlloc nor allowlisted [allowlist entry: ...]` | unknown callee |
| `... (it can be overridden: make it final/private or annotate it)` | same-file method that cannot be inferred |
| `call to 'X', which allocates: <reason> (line N)` | an inferred helper allocates |
| `a new 'Ptr' value is passed to 'g', which is not inlined ...` | Scala Native box escapes through a call |
| `a new 'Ptr' value is returned ...` | Scala Native box escapes through the result |
| `a new 'Ptr' value is stored in a field ...` | Scala Native box escapes into a field |
| `'X' allocates native heap memory (rejected by strict-heap)` | `malloc` & co. with `strict-heap` |
| `'f' overrides a @zeroAlloc method and must be annotated ...` | missing annotation on an override |
