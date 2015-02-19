package specs

import play.api.test.Helpers._
import triplestore.TripleStore

trait FakeTripleStore {
  val ts = TripleStore("http://localhost:3030/test", true)

  def cleanStart() = {
    await(ts.up.sparqlUpdate("DROP ALL"))
    if (countGraphs > 0) throw new RuntimeException
  }

  def countGraphs = {
    val graphs = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")).map(m => m("g"))
    println(graphs.mkString("\n"))
    graphs.size
  }


}
