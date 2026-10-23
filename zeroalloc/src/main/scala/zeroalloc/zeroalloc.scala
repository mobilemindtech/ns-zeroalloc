package zeroalloc

import scala.annotation.StaticAnnotation

/** Marks a method that must not allocate on the heap.
  *
  * The check itself is done by the `zeroalloc-plugin` compiler plugin; without
  * the plugin this annotation has no effect.
  */
final class zeroAlloc extends StaticAnnotation
