package example

import zeroalloc.zeroAlloc

case class User()
class Person

@zeroAlloc def another() = {
  println("hello, another!")
}

def withAllocFunc() = {
  new Person
}

@zeroAlloc def noAllocFunc() = {
  println("hello, noAllocFunc!")
  another()
  zeroalloc.unsafe {
    withAllocFunc()
    val user = new Person
    println("hello, unsafe!")
  }
}

@main def main() = {

  noAllocFunc()

}