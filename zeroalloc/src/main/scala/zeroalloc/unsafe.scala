package zeroalloc

/** Allows heap allocations inside a `@zeroAlloc` method.
  *
  * Kept in its own file: after inlining, the compiler only records the
  * top-level class of an inline call (`zeroalloc.unsafe$package`), which is
  * how the plugin recognizes this block.
  */
inline def unsafe[T](inline f: => T): T = f
