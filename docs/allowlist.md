# The allowlist

The plugin accepts a call to a library method only if it knows that method does not allocate on the GC heap. That knowledge lives in allowlist files: four built into the plugin, plus any you add.

* [File format](#file-format)
* [Built-in files](#built-in-files)
* [What is in the core list](#what-is-in-the-core-list)
* [What was left out, and why](#what-was-left-out-and-why)
* [Adding your own entries](#adding-your-own-entries)
* [Verifying the allowlist](#verifying-the-allowlist)

All verdicts below are for **Scala Native 0.5.12 in release mode**. They come from two sources:
1. reading the 0.5.12 sources of `javalib`, `nativelib`, `clib` and `auxlib`;
2. checking the optimized LLVM IR of small probes with `scripts/ir-allocs.py`. Where these disagreed, the IR wins: many methods that allocate in source (`Ptr` boxes, `Tag` instances) are allocation-free after Interflow.

## File format

One entry per line. `#` starts a comment.

```text
java.lang.Math.*                                   every method of the owner
java.lang.String.length                            every overload named `length`
java.lang.String.indexOf(int)                      one overload, by erased parameter types
scala.scalanative.unsafe.Tag.materializeCStruct*   every method whose name starts with the prefix
!scala.scalanative.unsafe.Ptr.toString             exclusion; wins over every other entry
```

* **Owner**: the fully qualified class or object. Objects are written without the trailing `$`; package objects as `pkg.package`; nested classes as `Outer$Inner`.
* **Name**: the method name as written in Scala (`+`, `unary_!`, `max$extension` for value-class extension methods).
* **Parameters** (optional): erased types separated by commas. Primitives are lower case (`int`, `char`, ...), arrays are `elem[]`, and everything else is fully qualified (`java.lang.String`, `scala.scalanative.unsafe.Ptr`).

You don't have to work out the entry yourself. When the plugin rejects a call, it prints the entry that would accept exactly that overload:

```
[zeroalloc] call to 'String.indexOf', which is not @zeroAlloc nor allowlisted
  [allowlist entry: java.lang.String.indexOf(java.lang.String)]
```

## Built-in files

They live in `plugin/src/main/resources/zeroalloc/allowlist/`.

| File | Used | Contents |
|---|---|---|
| `core.txt` | always | library methods that do not allocate |
| `native-ops.txt` | always | Scala Native operations: `Ptr`, `CArray`, `CStructN`, unsigned types, `Size`, `Tag` materializers, `Intrinsics`, package helpers. Their arguments are not treated as escaping (see [Scala Native boxes](plugin.md#scala-native-boxes)). |
| `throw.txt` | with `allow-throw` | methods that only allocate when they throw |
| `native-heap.txt` | with `strict-heap`, as a deny list | functions that allocate native (malloc) memory |

## What is in the core list

| Group | Methods | Evidence |
|---|---|---|
| `java.lang.Math`, `scala.math` | `abs`, `max`, `min`, `signum`, `ceil`, `floor`, `rint`, `round`, `sqrt`, `cbrt`, `pow`, `exp*`, `log*`, trigonometric and hyperbolic functions, `hypot`, `fma`, `floorDiv`, `floorMod`, `multiply*High`, `multiplyFull`, `ulp`, `next*`, `scalb`, `copySign`, `getExponent`, `IEEEremainder`, `toRadians`, `toDegrees` | LLVM intrinsics or libc calls. `max/min(Double)` and `round` box through `isNaN()` in source, but the IR in release mode is clean. |
| `Integer`, `Long` statics | bit operations (`bitCount`, `numberOf*Zeros`, `highest/lowestOneBit`, `rotate*`, `reverse*`), `signum`, `compare*`, `divideUnsigned`, `remainderUnsigned`, `toUnsignedLong`, `hashCode(prim)`, `sum`, `max`, `min` | pure arithmetic or intrinsics |
| `Double`, `Float` statics | `isNaN`, `isInfinite`, `isFinite` (primitive overloads), `compare`, `*ToRawLongBits`/`*ToRawIntBits`, `*ToLongBits`/`*ToIntBits`, `longBitsToDouble`, `intBitsToFloat`, `hashCode(prim)`, `sum`, `max`, `min` | cast intrinsics |
| `String` | `length`, `isEmpty`, `charAt`, `codePointAt`, `compareTo(String)`, `equals`, `hashCode`, `getChars(int,int,char[],int)`, `indexOf(int)`, `lastIndexOf(int)`, `lastIndexOf(int,int)` | IR clean; `charAt` allocates only to report an out-of-bounds index |
| `System`, `Thread` | `arraycopy`, `nanoTime`, `currentTimeMillis`, `identityHashCode`, `onSpinWait` | `@extern` calls or intrinsics; `arraycopy` allocates only when it throws |
| Atomics | `AtomicInteger`, `AtomicLong`, `AtomicBoolean`, `AtomicReference`: `get`, `set`, `lazySet`, `getAndSet`, `compareAndSet`, `weakCompareAndSet*`, `*AndGet`, `getAnd*`, `getPlain`/`setPlain`, `getAcquire`/`setRelease`, `getOpaque`/`setOpaque` | IR clean (the internal `Ptr` is removed by Interflow) |
| `ByteBuffer`, `Buffer` | `get()`, `get(int)`, `put(byte)`, `put(int,byte)`, `get*`/`put*` of `Char`/`Short`/`Int`/`Long`/`Float`/`Double`, `position`, `limit`, `capacity`, `remaining`, `hasRemaining`, `clear`, `flip`, `rewind`, `mark`, `reset` | IR clean; `put*` allocates only on a read-only buffer |
| Scala runtime | `ScalaRunTime.array_length/array_update`, `Statics.mix`/`mixLast`/`finalizeHash`/`avalanche`/`longHash`/`doubleHash`/`floatHash` | pure arithmetic |
| Value-class wrappers | `intWrapper` & co., `RichInt`/`RichLong`/`RichDouble`/`RichFloat` `max`, `min`, `abs`, `isWhole`, `round`, `ceil`, `floor`, `isNaN`, `isInfinity`, `toRadians`, `toDegrees`; `RichChar.max/min`; `intArrayOps` & co. with `isEmpty`, `nonEmpty`, `size`, `knownSize` | value classes are erased |

## What was left out, and why

| Method | Why |
|---|---|
| `String.indexOf(String)`, `indexOf(String,int)`, `contains` | `memmem` builds a closure and boxes `Ptr`/`Size` on every call (IR: `Ptr`, `Size`, `ClassTag`). |
| `String.indexOf(int,int)` | `Math.clamp` boxes its arguments. |
| `String.startsWith`, `endsWith`, `regionMatches`, `equalsIgnoreCase`, `compareToIgnoreCase` | The shared implementation reaches the `Character` case tables, boxing and `substring` (IR). |
| `String.contentEquals`, `toUpperCase`, `substring`, `trim`, `split`, `format`, `+` | They build strings or call `toString`. |
| `Character.isDigit`, `isLetter`, `toUpperCase`, `toLowerCase`, `digit`, ... | Allocation-free only for Latin-1. Above that they call `Arrays.binarySearch` (closure plus boxing), and the first use builds the tables. Compare ASCII ranges by hand instead. |
| `java.util.Arrays.fill`, `equals`, `hashCode`, `binarySearch` | Generic implementations: boxing, closures, `Range` (IR: `Integer`, `ObjectArray`). |
| `Math.clamp` | Boxes through `compareTo`. |
| `Math.random` | Creates a `java.util.Random` on first use, and is not thread-safe. |
| `java.lang.StrictMath` | Not implemented in Scala Native 0.5.12; it would not link. |
| `Integer.valueOf`, `toString`, `parseInt`, ... | Boxing or strings. |
| `Thread.currentThread` | The first call initializes the main thread (`Thread`, `ThreadGroup`, `ThreadLocal`), and `getId` boxes a `USize`. |
| `RichInt.sign`, `RichDouble.sign`, `RichInt.isValidChar`, ... | Go through `Numeric` or box the value class. |
| `ArrayOps.head`, `last`, and anything generic on `ArrayOps` | `ArrayOps[A]` is not specialized; elements are boxed. |
| `ByteBuffer` bulk `get`/`put`, `slice`, `duplicate`, `wrap`, `allocate*` | They create buffers or were not verified. |
| `atomic.*.updateAndGet`, `accumulateAndGet`, ... | They take a function. |
| `fromCString`, `toCString`, `fromCWideString`, `toCVarArgList` | They build strings or allocate in a `Zone`. |
| `alloc[T]` (`Zone`), `Zone.open`, `Zone { ... }` | `MemoryPoolZone` allocates GC objects (pages, `USize` offsets) and the block is a closure. |
| `Tag.materializeCFuncPtr*`, `Tag.materializeClassTag` | Anonymous classes or `ClassTag`; not verified. |
| `Ptr.toString`, `UInt.toString`, ... | They build strings (excluded with `!` entries). |

## Adding your own entries

Write a file in the same format and pass it to the plugin:

```scala
scalacOptions += s"-P:zeroalloc:allowlist:${baseDirectory.value}/zeroalloc-allowlist.txt"
```

Use `-P:zeroalloc:allow:<Owner>` to accept a whole class or object. This is handy for your own libraries compiled in a separate module, where annotating with `@zeroAlloc` is better still.

An entry is a promise you make to the plugin. Check it the same way the built-in list was checked.

## Verifying the allowlist

`conformance/` is a small program made of `@zeroAlloc` methods that exercise the built-in rules. It is linked in `releaseFast` mode, and `scripts/ir-allocs.py` checks that none of its methods reaches a GC allocation in the generated LLVM IR:

```bash
sbt conformance/nativeLink
python3 scripts/ir-allocs.py \
  target/out/native0.5/scala-3.8.4/zeroalloc-conformance/native/generated \
  '_SM\d+Conformance\$D(?!4main)'
```

```
atomic                   ok        indirect-calls=0
buffer                   ok        indirect-calls=0
    when throws java.nio.ReadOnlyBufferException
ptrOps                   ok        indirect-calls=0
...
```

How the script works:
* For each matching function it follows the direct calls (up to depth 8) and collects the classes passed to the GC allocator.
* Exceptions, and the strings built for their messages, are listed under `when throws`.
* Anything else is listed under `allocates`, and the script then exits with status 1.
* `indirect-calls` counts virtual calls it could not follow. Look at those by hand.
* Methods that Interflow inlined completely have no function of their own in the IR and do not show up.

**When to run it:**
* when upgrading Scala Native or Scala;
* after adding a built-in entry. Add a method using the entry to `Conformance.scala`, link, and check the script's output.

To check a method that the plugin rejects, put it in a scratch program without `@zeroAlloc` and run the script on it. That is how the "left out" table above was produced.
