package zeroalloc

import munit.FunSuite
import scala.annotation.experimental

@experimental
class RuntimeSuite extends FunSuite {

  // Função anotada válida
  @zeroAlloc
  def safeAdd(a: Int, b: Int): Int = a + b

  // Função utilizando bloco unsafe
  @zeroAlloc
  def unsafeOperation(): String = {
    zeroalloc.unsafe {
      s"String criada com unsafe: ${10 + 20}"
    }
  }

  test("deve executar funções com @zeroAlloc sem alocação") {
    val result = safeAdd(10, 20)
    assertEquals(result, 30)
  }

  test("deve permitir alocação quando protegida por bloco unsafe") {
    val result = unsafeOperation()
    assert(result.contains("30"))
  }
}