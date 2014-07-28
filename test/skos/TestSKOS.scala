//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package skos

import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json
import services.{LabelSearch, SkosJson, SkosVocabulary}

import scala.io.Source

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

class TestSKOS extends FlatSpec with Matchers with SkosJson {

  "A Source" should "be readable" in {
    val example = getClass.getResource("/skos-example.xml")
    val source = Source.fromInputStream(example.openStream())
    val conceptScheme = SkosVocabulary(source)

    def searchConceptScheme(sought: String) = conceptScheme.search("dut", sought, 3)

    val searches: List[LabelSearch] = List(
      "Europese wetgeving",
      "bezoeken",
//      "bezoiken",
//      "geografische bevoegdheid",
//      "herwoorderingspolitiek",
      "wetgevingen"
    ).map(searchConceptScheme)

    searches.foreach(s => println(Json.prettyPrint(Json.obj("search" -> s))))

//    stack.pop() should be(1)
  }

}
