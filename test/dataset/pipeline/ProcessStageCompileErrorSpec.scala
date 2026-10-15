package dataset.pipeline

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProcessStageCompileErrorSpec extends AnyFlatSpec with Matchers {

  private def compileFailure(script: String): Throwable =
    intercept[Throwable](new groovy.lang.GroovyShell().parse(script))

  "ProcessStage.compileError" should "give the compiler's own line" in {
    val e = new RuntimeException("[Compilation error] Failed to compile mapping script",
      compileFailure("def x = { a -> a }\nfoo('rdf:resource': 1, 'rdf:resource': 2)\n"))
    ProcessStage.compileError(e) should startWith("Duplicated named parameter 'rdf:resource'")
  }

  it should "keep a quoted-script message short" in {
    val e = compileFailure("x = (\n" + ("'a' : 1,\n" * 200) + "\n")
    ProcessStage.compileError(e).length should be <= 200
  }
}
