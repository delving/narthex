package services

import javax.inject._
import java.io.{PrintWriter, StringWriter}

import play.api.Logger
import play.api.libs.mailer._

import scala.concurrent.ExecutionContext

import init.NarthexConfig


trait MailService {

  def sendProcessingCompleteMessage(spec: String,
                              validString: String,
                              invalidString: String): Unit

  def sendProcessingErrorMessage(spec: String,
                                 message: String,
                                 throwableOpt: Option[Throwable]): Unit
}

class PlayMailService @Inject() (val mailerClient: MailerClient, narthexConfig: NarthexConfig)
                     (implicit val ec: ExecutionContext)
  extends MailService {

  private val logger = Logger(getClass)

  val adminEmails: List[String] = narthexConfig.emailReportsTo

  val fromNarthex = "Narthex <narthex@delving.eu>"

  override def sendProcessingCompleteMessage(spec: String, validString: String, invalidString: String) = {
    val subject = s"Processing Complete: $spec"
    val html = views.html.email.processingComplete.render(spec, validString, invalidString).body
    sendMail(subject, html)
  }

  override def sendProcessingErrorMessage(spec: String, message: String, throwableOpt: Option[Throwable]) = {
    def exceptionString = throwableOpt.map { throwable =>
      val sw = new StringWriter()
      val out = new PrintWriter(sw)
      throwable.printStackTrace(out)
      sw.toString.split("\n").map(_.trim).map(line => if (line.startsWith("at ")) s"    $line" else line).mkString("\n")
    } getOrElse {
      "No exception"
    }

    val subject = s"Failure in dataset: $spec"
    val html = views.html.email.datasetError.render(spec, message, exceptionString).body
    sendMail(subject, html)
  }


  private def sendMail(subject: String, html: String): Unit = {
    if (adminEmails.isEmpty) {
      logger.warn(s"No emailReportsTo configured, not sending")
    } else {
      val email = Email(to = adminEmails, from = fromNarthex, subject = subject, bodyHtml = Some(html))
      val messageId = mailerClient.send(email)
      logger.debug(s"Sent email $messageId")
    }
  }


}
