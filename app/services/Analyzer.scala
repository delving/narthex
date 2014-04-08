package services

import akka.actor.Actor
import java.io.{FileInputStream, File}
import scala.xml.pull.{EvElemEnd, EvElemStart, XMLEventReader}
import scala.io.Source
import play.api.Logger
import java.util.zip.GZIPInputStream
import scala.collection._

class Analyzer extends Actor {

  override def receive = {
    case AnalyzeFile(file) => {
      val xml = new XMLEventReader(Source.fromInputStream(new GZIPInputStream(new FileInputStream(file))))
      var path = mutable.Stack[String]()
      xml.foreach{
        case EvElemStart(pre, label, attrs, scope) => {
//          Logger.info(s"START $pre $label $attrs $scope")
          path.push(label)
          Logger.info(s"${path.reverse.mkString(",")}")
          // START null instance_generator  name="uriConceptual"
        }
        case EvElemEnd(pre, label) => {
          path.pop()
//          Logger.info(s"END $pre $label")
          // START null instance_generator  name="uriConceptual"
        }
        case _ =>
      }
    }
  }
}

case class AnalyzeFile(file: File)

/*
                while (running) {
                    switch (input.getEventType()) {
                        case XMLEvent.START_ELEMENT:
                            if (++count % ELEMENT_STEP == 0) {
                                if (listener != null) progressListener.setProgress(count);
                            }
                            for (int walk = 0; walk < input.getNamespaceCount(); walk++) {
                                stats.recordNamespace(input.getNamespacePrefix(walk), input.getNamespaceURI(walk));
                            }
                            String chunk = text.toString().trim();
                            if (!chunk.isEmpty()) {
                                stats.recordValue(path, chunk);
                            }
                            text.setLength(0);
                            path = path.child(Tag.element(input.getName()));
                            if (input.getAttributeCount() > 0) {
                                for (int walk = 0; walk < input.getAttributeCount(); walk++) {
                                    QName attributeName = input.getAttributeName(walk);
                                    Path withAttr = path.child(Tag.attribute(attributeName));
                                    stats.recordValue(withAttr, input.getAttributeValue(walk));
                                }
                            }
                            break;
                        case XMLEvent.CHARACTERS:
                        case XMLEvent.CDATA:
                            text.append(input.getText());
                            break;
                        case XMLEvent.END_ELEMENT:
                            stats.recordValue(path, text.toString().trim());
                            text.setLength(0);
                            path = path.parent();
                            break;
                    }
                    if (!input.hasNext()) break;
                    input.next();
                }

 */