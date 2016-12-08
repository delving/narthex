package services

import java.io.{PrintWriter, StringWriter}

import organization.UserRepository
import play.api.Logger
import play.api.libs.mailer._

import scala.concurrent.{ExecutionContext, Future}


trait MailService {

  def sendProcessingCompleteMessage(spec: String,
                              ownerEmailOpt: Option[String],
                              validString: String,
                              invalidString: String): Unit

  def sendProcessingErrorMessage(spec: String,
                                 ownerEmailOpt: Option[String],
                                 message: String,
                                 throwableOpt: Option[Throwable]): Unit
}

class MailServiceImpl(val mailerClient: MailerClient, val userRepository: UserRepository, isProduction: Boolean)
                     (implicit val ec: ExecutionContext)
  extends MailService {

  val fromNarthex = "Narthex <narthex@delving.eu>"

  override def sendProcessingCompleteMessage(spec: String, ownerEmailOpt: Option[String], validString: String, invalidString: String) = {
    val subject = s"Processing Complete: $spec"
    val html = views.html.email.processingComplete.render(spec, validString, invalidString).body
    sendMail(ownerEmailOpt, subject, html)
  }

  override def sendProcessingErrorMessage(spec: String, ownerEmailOpt: Option[String], message: String, throwableOpt: Option[Throwable]) = {
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
    sendMail(ownerEmailOpt, subject, html)
  }


  private def sendMail(toOpt: Option[String], subject: String, html: String): Unit = {
    val emailList = prepareRecipients(toOpt)

    emailList.map { recipients =>
      if (recipients.isEmpty) {
        Logger.debug(s"EMail: '$subject' not sent because there is no recipient email address available.")
      } else {
        val email = Email(to = recipients, from = fromNarthex, subject = subject, bodyHtml = Some(html))
        mailerClient.send(email)
      }
    }
  }

  private def prepareRecipients(overrideTo: Option[String]): Future[List[String]] = {
    overrideTo match {
      case Some(to) => Future.successful(List(to))
      case None =>
        userRepository.adminEmails
    }
  }

}
