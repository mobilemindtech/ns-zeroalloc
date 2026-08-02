package zeroalloc

import munit.FunSuite
import scala.annotation.experimental

class Person
case class User(name: String)

@experimental
class MacroCompileSuite extends FunSuite {

  test("1. Deve falhar ao usar 'new' fora do unsafe") {
    val errors = compileErrors("""
      import zeroalloc.*
      import scala.annotation.experimental

      @experimental
      @zeroAlloc
      def invalidNew(): Unit = {
        val p = new Person()
      }
    """)

    // Se o teste falhar, exibe o conteúdo real de 'errors'
    assert(
      errors.contains("[ZeroAlloc] Alocação na Heap detectada com 'new"),
      s"\n❌ O erro obtido foi diferente do esperado:\n$errors"
    )
  }

  test("2. Deve falhar ao chamar 'apply' de case class (User())") {
    val errors = compileErrors("""
      import zeroalloc.*
      import scala.annotation.experimental

      @experimental
      @zeroAlloc
      def invalidCaseClass(): Unit = {
        val u = User("Alice")
      }
    """)

    assert(
      errors.contains("[ZeroAlloc] Chamada insegura para o método 'apply'"),
      s"\n❌ O erro obtido foi diferente do esperado:\n$errors"
    )
  }

  test("3. Deve falhar ao usar interpolação de String (s\"...\")") {
    val errors = compileErrors("""
      import zeroalloc.*
      import scala.annotation.experimental

      @experimental
      @zeroAlloc
      def invalidString(): Unit = {
        val x = 10
        val str = s"valor: $x"
      }
    """)

    assert(
      errors.contains("[ZeroAlloc] Interpolação de String aloca na Heap"),
      s"\n❌ O erro obtido foi diferente do esperado:\n$errors"
    )
  }

  test("4. Deve PASSAR na compilação se a alocação estiver dentro de 'unsafe'") {
    val errors = compileErrors("""
      import zeroalloc.*
      import scala.annotation.experimental

      @experimental
      @zeroAlloc
      def validUnsafe(): Unit = {
        zeroalloc.unsafe {
          val p = new Person()
        }
      }
    """)

    // Esperado: NENHUM erro de compilação (string vazia)
    assertEquals(errors, "", s"\n❌ A compilação falhou quando deveria ter passado:\n$errors")
  }
}