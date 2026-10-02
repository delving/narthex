package services

import javax.inject._
import java.io.{PrintWriter, StringWriter}

import play.api.Logger
import play.api.libs.mailer._

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import init.NarthexConfig


trait MailService {

  def sendProcessingErrorMessage(spec: String,
                                 message: String,
                                 throwableOpt: Option[Throwable]): Unit

  /** Send whatever failures have accumulated, as one mail. Called on a timer by
    * OrgContext; nothing leaves this service until it runs. */
  def flushPending(): Unit
}

object PlayMailService {

  /** Beyond this many datasets the mail lists the first ones and a count. A
    * source outage hits every dataset that uses it, so the list is unbounded in
    * principle; a mail nobody scrolls through is no better than no mail. */
  val MaxDatasetsListed = 50
}

class PlayMailService @Inject() (val mailerClient: MailerClient, narthexConfig: NarthexConfig)
                     (implicit val ec: ExecutionContext)
  extends MailService {

  private val logger = Logger(getClass)

  val adminEmails: List[String] = narthexConfig.emailReportsTo

  val fromNarthex = "Narthex <narthex@delving.eu>"

  /** One entry per dataset, in the order the datasets first failed. A dataset
    * that fails forty times in one window is one entry with count 40, not forty
    * mails -- which is what a harvest with retries produces. */
  private case class Pending(count: Int, message: String, exceptionString: String)

  private val pending = mutable.LinkedHashMap.empty[String, Pending]

  override def sendProcessingErrorMessage(spec: String, message: String, throwableOpt: Option[Throwable]): Unit = {
    val exceptionString = throwableOpt.map { throwable =>
      val sw = new StringWriter()
      val out = new PrintWriter(sw)
      throwable.printStackTrace(out)
      sw.toString.split("\n").map(_.trim).map(line => if (line.startsWith("at ")) s"    $line" else line).mkString("\n")
    } getOrElse {
      "No exception"
    }

    pending.synchronized {
      val previous = pending.get(spec)
      // Keep the newest message -- it is the one the reader will act on -- but
      // the first exception, since later retries of the same failure carry the
      // same trace and the first one is closest to the cause.
      pending.update(spec, Pending(
        count = previous.map(_.count).getOrElse(0) + 1,
        message = message,
        exceptionString = previous.map(_.exceptionString).getOrElse(exceptionString)
      ))
    }
  }

  override def flushPending(): Unit = {
    val batch = pending.synchronized {
      val taken = pending.toList
      pending.clear()
      taken
    }

    if (batch.isEmpty) return

    if (batch.size == 1) {
      // A single dataset keeps the old subject and the old template, so mail
      // filters that were set up around them keep matching.
      val (spec, p) = batch.head
      val repeated = if (p.count > 1) s" (${p.count}x)" else ""
      sendMail(
        s"Failure in dataset: $spec$repeated",
        views.html.email.datasetError.render(spec, p.message, p.exceptionString).body
      )
    } else {
      val listed = batch.take(PlayMailService.MaxDatasetsListed)
      val rows = listed.map { case (spec, p) => (spec, p.count, p.message) }
      val failures = batch.map(_._2.count).sum
      sendMail(
        s"Narthex: ${batch.size} datasets met fouten",
        views.html.email.datasetErrorDigest.render(rows, batch.size - listed.size, failures).body
      )
    }
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
