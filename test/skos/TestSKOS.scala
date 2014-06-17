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
import services.SkosHandling

import scala.io.Source

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

class TestSKOS extends FlatSpec with Matchers with SkosHandling {

  "A Source" should "be readable" in {
    val example = getClass.getResource("/skos-example.xml")
    val source = Source.fromInputStream(example.openStream())
    val vocab = SkosVocabulary(source)
    println(s"VOCAB $vocab")
//    stack.pop() should be(1)
  }

}
