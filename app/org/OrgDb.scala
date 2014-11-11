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

package org

import org.OrgDb.Dataset
import org.basex.server.ClientSession
import services.BaseX

import scala.xml.{Elem, XML}

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

object OrgDb {

  case class Dataset(datasetName: String, info: Elem)

}

class OrgDb(val orgId: String) {

  val datasetDb = s"narthex_$orgId"
  val name = "narthex-datasets"
  val allDatasets = s"doc('$datasetDb/$datasetDb.xml')/$name"

  def db[T](block: ClientSession => T): T = BaseX.withDbSession[T](datasetDb, Some(name))(block)

  def listDatasets: Seq[Dataset] = db { session =>
    val answer = session.query(allDatasets).execute()
    val wholeFile = XML.loadString(answer)
    val datasets = wholeFile \ "dataset"
    datasets.map(node => Dataset((node \ "@name").text, node.asInstanceOf[Elem])).sortBy(_.datasetName.toLowerCase)
  }

}
