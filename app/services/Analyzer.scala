package services

import akka.actor.Actor
import java.io.{FileInputStream, File}
import scala.io.Source
import java.util.zip.GZIPInputStream

class Analyzer extends Actor with XRay {

  var elementProgress: Long = 0
  var node: XRayNode = null

  def progress(elementCount: Long): Boolean = {
    elementProgress = elementCount
    true
  }

  override def receive = {
    case Initialize(file) =>
      if (elementProgress == 0) {
        val source = Source.fromInputStream(new GZIPInputStream(new FileInputStream(file)))
        node = XRayNode(source, progress)
      }
    case GetStatus =>
      sender ! XRayStatus(node, elementProgress, false)
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