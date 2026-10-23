import scala.scalanative.annotation.alwaysinline
import scala.scalanative.libc
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import zeroalloc.zeroAlloc

import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

/** Methods the plugin accepts, linked in release mode so that
  * `scripts/ir-allocs.py` can check that none of them allocates.
  * See docs/allowlist.md, "Verifying the allowlist".
  */
object Conformance {
  @scala.noinline @zeroAlloc def sink(p: Ptr[Int]): Int = !p
  @scala.noinline @zeroAlloc def give(p: Ptr[Int]): Ptr[Int] = p
  @alwaysinline @zeroAlloc def inl(p: Ptr[Int]): Int = !p + 1

  // Scala Native boxes at method boundaries
  @scala.noinline @zeroAlloc def passParam(p: Ptr[Int]): Int = sink(p)
  @scala.noinline @zeroAlloc def passCallResult(p: Ptr[Int]): Int = sink(give(p))
  @scala.noinline @zeroAlloc def passFreshToInline(p: Ptr[Int]): Int = inl(p + 1)
  @scala.noinline @zeroAlloc def returnParam(p: Ptr[Int]): Ptr[Int] = p

  // Scala Native operations
  @scala.noinline @zeroAlloc def ptrOps(p: Ptr[Int], v: Int): Int = { p(0) = v; !p + p(1) + !(p + 2) }
  @scala.noinline @zeroAlloc def struct(p: Ptr[CStruct2[Int, Long]], v: Int): Long = { p._1 = v; p._1 + p._2 }
  @scala.noinline @zeroAlloc def carray(p: Ptr[CArray[Int, Nat._8]]): Int = { val a = !p; a(1) = 3; a(1) + a.length }
  @scala.noinline @zeroAlloc def stack(n: Int): Long = {
    val p = stackalloc[Int](4); val q = stackalloc[Byte](n); p(1) = n
    p(1) + q.toLong + sizeof[CStruct2[Int, Long]].toLong
  }
  @scala.noinline @zeroAlloc def unsigned(a: UInt, b: CSize): Long =
    (a + 2.toUInt * a).toLong + (b + 1.toUSize).toLong + (a.toULong >> 2).toLong
  @scala.noinline @zeroAlloc def extern(p: Ptr[Byte], n: CSize): Unit = libc.string.memset(p + 1, 0, n)
  @scala.noinline @zeroAlloc def nativeHeap(n: CSize): Unit = { val p = libc.stdlib.malloc(n); libc.stdlib.free(p) }

  // allowlist
  @scala.noinline @zeroAlloc def mathD(a: Double, b: Double): Double =
    Math.max(a, b) + Math.round(a) + Math.sqrt(b) + math.floor(a) + a.abs
  @scala.noinline @zeroAlloc def mathI(a: Int, b: Int): Int = Math.abs(a) + Math.max(a, b) + (a max b) + Math.floorMod(a, 7)
  @scala.noinline @zeroAlloc def bits(a: Int, d: Double): Long =
    Integer.bitCount(a) + java.lang.Long.numberOfTrailingZeros(a.toLong) + java.lang.Double.doubleToRawLongBits(d) +
      (if (java.lang.Double.isNaN(d)) 1 else 0)
  @scala.noinline @zeroAlloc def string(s: String, t: String): Int =
    s.length + s.charAt(0) + s.indexOf('x'.toInt) + s.compareTo(t) + s.hashCode + (if (s.equals(t)) 1 else 0)
  @scala.noinline @zeroAlloc def system(a: Array[Int], b: Array[Int]): Long = {
    System.arraycopy(a, 0, b, 0, 2); System.nanoTime() + System.identityHashCode(a)
  }
  @scala.noinline @zeroAlloc def atomic(a: AtomicInteger, l: AtomicLong): Long =
    a.incrementAndGet() + a.get() + (if (a.compareAndSet(1, 2)) 1 else 0) + l.getAndAdd(3L)
  @scala.noinline @zeroAlloc def buffer(b: ByteBuffer): Long = { b.putLong(0, 5L); b.getInt(0) + b.get(1) + b.position() + b.remaining() }
  @scala.noinline @zeroAlloc def arrayOps(a: Array[Int]): Boolean = a.isEmpty || a.nonEmpty
  @scala.noinline @zeroAlloc def statics(a: Long): Int = scala.runtime.Statics.mix(1, scala.runtime.Statics.longHash(a))

  // language
  @scala.noinline @zeroAlloc def loop(a: Array[Int]): Int = { var i = 0; var s = 0; while (i < a.length) { s += a(i); i += 1 }; s }
  @scala.noinline @zeroAlloc def inferred(a: Int): Int = helper(a) * 2
  private def helper(a: Int): Int = a * a + 1
  @scala.noinline @zeroAlloc def throwing(a: Int): Int = {
    require(a > 0)
    if (a > 100) throw new IllegalStateException(s"too big: $a")
    Math.addExact(a, 1)
  }

  def main(args: Array[String]): Unit = {
    val p = stackalloc[Int](8)
    val st = stackalloc[CStruct2[Int, Long]]()
    val ca = stackalloc[CArray[Int, Nat._8]]()
    val b = stackalloc[Byte](16)
    val n = args.length
    val s = args.mkString + "ab"
    val arr = new Array[Int](8)
    val arr2 = new Array[Int](8)
    var r = 0L
    r += passParam(p) + passCallResult(p) + passFreshToInline(p) + !returnParam(p)
    r += ptrOps(p, n) + struct(st, n) + carray(ca) + stack(n + 1) + unsigned(n.toUInt, n.toUSize)
    extern(b, 4.toUSize)
    nativeHeap(16.toUSize)
    r += (mathD(n, 2.0) + mathI(n, 3) + bits(n, n.toDouble) + string(s, "x")).toLong
    r += system(arr, arr2) + atomic(new AtomicInteger(n), new AtomicLong(n)) + buffer(ByteBuffer.allocate(16))
    r += (if (arrayOps(arr)) 1 else 0) + statics(r) + loop(arr) + inferred(n) + throwing(n + 1)
    println(r)
  }
}
