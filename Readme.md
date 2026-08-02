# zeroalloc

A compile-time macro annotation for Scala 3 and Scala Native that strictly enforces **Zero Heap Allocation** within annotated methods.

---

## 🛠️ Overview

In systems programming, low-latency applications, and embedded target environments (e.g., Scala Native), heap allocations introduce garbage collection overhead and non-deterministic latency spikes.

`@zeroAlloc` acts as a compile-time barrier. It inspects the Abstract Syntax Tree (AST) of annotated methods and fails the compilation if any code path allocates memory on the heap.

```scala
import zeroalloc.*
import scala.annotation.experimental

@experimental
@zeroAlloc
def processSignal(input: Int): Int = {
  // ✅ OK: Primitive stack operations
  val result = input * 2

  // ❌ Compile Error: 'new' triggers heap allocation!
  val person = new Person() 

  result
}


---

## 🚀 Features

* **Compile-Time Validation**: Catch heap allocations during compilation—zero runtime overhead.
* **Heap Instantiation Checks**: Blocks direct `new` object creation.
* **Case Class & Companion Method Guards**: Detects implicit heap allocations via factory methods (e.g., `User(...)`).
* **String Context Protection**: Rejects heap-allocating string interpolations (e.g., `s"Value: $x"`).
* **Escape Hatch (`unsafe`)**: Allows controlled local heap allocations when explicitly wrapped in `zeroalloc.unsafe { ... }`.
* **Scala Native Friendly**: Fully compatible with Scala Native stack/primitive abstractions.

---

## 📥 Installation

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.github.zeroalloc" %%% "zeroalloc" % "0.1.0"

// Required for Scala 3 MacroAnnotations
scalacOptions += "-experimental"

```

---

## 💡 Usage Examples

### 1. Stack & Primitive Operations (Allowed ✅)

Primitive calculations, passing value parameters, and calls to other `@zeroAlloc` methods pass validation.

```scala
import zeroalloc.*
import scala.annotation.experimental

@experimental
@zeroAlloc
def add(a: Int, b: Int): Int = a + b

@experimental
@zeroAlloc
def compute(): Int = {
  val x = 10
  val y = 20
  add(x, y) // ✅ Allowed: Calling another @zeroAlloc method
}

```

### 2. String Literals vs. String Interpolation

Static string literals are backed by the constant pool and do not allocate at runtime, whereas dynamic interpolations create `StringBuilder` or `StringContext` instances.

```scala
@experimental
@zeroAlloc
def stringHandling(): Unit = {
  val staticStr = "Hello, World!" // ✅ Allowed: String Pool constant

  val x = 42
  val dynamicStr = s"Value: $x"  // ❌ Compile Error: String interpolation allocates on the Heap!
}

```

### 3. Case Classes & Direct Object Creation

Creating instances of classes or calling companion factory methods allocates memory on the heap.

```scala
class Person
case class User(name: String)

@experimental
@zeroAlloc
def createEntities(): Unit = {
  val p = new Person() // ❌ Compile Error: Allocation detected with 'new Person'
  val u = User("Alice") // ❌ Compile Error: Unsafe call to companion method 'apply'
}

```

### 4. Bypassing Restrictions with `unsafe`

When interacting with legacy APIs or performing one-off setup tasks where heap allocations are acceptable, wrap the allocating code inside `zeroalloc.unsafe`.

```scala
@experimental
@zeroAlloc
def processWithUnsafeEscape(): Int = {
  val count = 100

  // Temporarily bypass zero-allocation checks
  zeroalloc.unsafe {
    println(s"Debug logging count: $count") // Heap allocation isolated here
    val tempPerson = new Person()
  }

  count * 2 // Back under strict zero-allocation enforcement
}

```

---

## 🔍 How It Works

During macro expansion (`quotes.reflect`), the `@zeroAlloc` annotation traverses the method's `Definition`:

1. **`Inlined` Nodes**: Checks if an inlined block originates from `zeroalloc.unsafe`. If so, subtree traversal is skipped.
2. **`New` Term Inspection**: Aborts compilation if a `New(tpt)` AST node is encountered outside of an `unsafe` block.
3. **`Apply` Method Checks**: Inspects target symbols. Rejects methods originating from `scala.StringContext` or functions not annotated with `@zeroAlloc`.

---

## 🧪 Running Tests

To run the test suite (powered by **MUnit**):

```bash
sbt "tests/test"

```

To run macro compilation assertions individually:

```bash
sbt "tests/testOnly zeroalloc.MacroCompileSuite"

```

---

## 📄 License

This project is licensed under the [Apache 2.0 License](https://www.google.com/search?q=LICENSE).
