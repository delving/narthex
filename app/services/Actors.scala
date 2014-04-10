package services

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.actor.{Props, ActorLogging, Actor}
import java.io.{FileInputStream, File}
import scala.language.postfixOps
import scala.io.Source
import java.util.zip.GZIPInputStream
import play.api.libs.json._
import org.apache.commons.io.FileUtils

case class AnalyzeThese(jobs: List[(File, File)])

case class Analyze(file: File, directory: File)

case class AnalysisComplete(json: JsValue, directory: File)

case class Progress(progressCount: Long, directory: File)

class Boss extends Actor with ActorLogging {

  override def receive: Receive = {

    case AnalyzeThese(jobs) =>
      log.info(s"Boss has ${jobs.size} jobs to do")
      jobs.map {
        job =>
          val analyzer = context.actorOf(Props[Analyzer], job._1.getName)
          analyzer ! Analyze(job._1, job._2)
      }

    case Progress(count, directory) =>
      val statusFile: File = new File(directory, FileRepository.statusFileName)
      val json = Json.obj("count" -> count, "complete" -> (count < 0))
      FileUtils.writeStringToFile(statusFile, Json.prettyPrint(json), "UTF-8")

    case AnalysisComplete(json, directory) =>
      log.info(s"AnalysisComplete at ${directory.getName}")
      val pretty = Json.prettyPrint(json)
      val analysisFile = new File(directory, FileRepository.analysisFileName)
      FileUtils.writeStringToFile(analysisFile, pretty, "UTF-8")
  }
}


class Analyzer extends Actor with XRay with ActorLogging {

  def receive = {

    case Analyze(file, directory) =>
      log.info(s"Analyzer looking at ${file.getName}")
      val source = Source.fromInputStream(new GZIPInputStream(new FileInputStream(file)))
      val root: XRayNode = XRayNode(source, {
        count =>
          sender ! Progress(count, directory)
      })
      sender ! AnalysisComplete(Json.toJson(root), directory)

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