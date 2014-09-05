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

package services

import org.basex.core.BaseXException
import org.basex.server.ClientSession

import scala.xml.{Elem, XML}

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

class RepoDb(val orgId: String) {

  def datasetDb = s"narthex_$orgId"

  def allDatasets = s"doc('$datasetDb/$datasetDb.xml')/narthex-datasets"

  case class Dataset(name: String, info: Elem)

  def db[T](block: ClientSession => T): T = {
    try {
      BaseX.withDbSession[T](datasetDb)(block)
    }
    catch {
      case be: BaseXException =>
        if (be.getMessage.contains("not found")) {
          BaseX.createDatabase(datasetDb, "<narthex-datasets/>")
          BaseX.withDbSession[T](datasetDb)(block)
        }
        else {
          throw be
        }
    }
  }

  def listDatasets: Seq[Dataset] = db {
    session =>
      val answer = session.query(allDatasets).execute()
      val wholeFile = XML.loadString(answer)
      val datasets = wholeFile \ "dataset"
      datasets.map(node => Dataset((node \ "@name").text, node.asInstanceOf[Elem]))
  }

}
