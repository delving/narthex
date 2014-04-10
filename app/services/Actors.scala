package services

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.actor.{ActorLogging, Actor}
import java.io.{FileInputStream, File}
import scala.io.Source
import java.util.zip.GZIPInputStream
import scala.concurrent.Future

case class Initialize(file: File)

case class Progress(progressCount: Long, completed: Boolean)

case class GetStatus()

class Analyzer extends Actor with XRay with ActorLogging {
  var elementProgress: Long = 0
  var tree: Option[XRayNode] = None

  def progress(elementCount: Long): Boolean = {
    elementProgress = elementCount
    true
  }

  def receive = {

    case Initialize(file) =>
      log.info("Initializing!")
      if (!tree.isDefined) {
        val futureTree: Future[XRayNode] = Future {
          val source = Source.fromInputStream(new GZIPInputStream(new FileInputStream(file)))
          XRayNode(source, progress)
        }
        futureTree.map(createdTree => tree = Some(createdTree))
      }

    case GetStatus() =>
      log.info("Get status message came in!")
      sender ! Progress(elementProgress, tree.isDefined)

    case x =>
      log.info("WHAT? " + x)

  }
}


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