package services

import dataset.DsInfo
import play.api.Logger
import play.api.Play.current
import play.api.libs.mailer.{Email, MailerPlugin}

import scala.concurrent.ExecutionContext

object EMail {

  private def send(to: String, subject: String, html: String)(implicit ec: ExecutionContext) = MailerPlugin.send(Email(
    to = Seq(to),
    from = "Narthex <narthex@delving.eu>",
    subject = subject,
    bodyHtml = Some(html)
  ))

  def sendProcessingComplete(dsInfo: DsInfo)(implicit ec: ExecutionContext) = dsInfo.ownerEmailOpt.map { email =>
    send(
      to = email,
      subject = s"Processing Complete: $dsInfo",
      html =
        s"""
          |<html>
          |<body>
          |<h2>Processing of dataset $dsInfo completed</h2>
          |<div>
          |<ul>
          |  <li>
          |   <strong>Valid records: </strong> <span>${dsInfo.processedValidVal}</span>
          |  </li>
          |  <li>
          |   <strong>Invalid records: </strong> <span>${dsInfo.processedInvalidVal}</span>
          |  </li>
          |</ul>
          |</div>
          |<div>
          |  <small>Cheers,</small>
          |</div>
          |<div>
          |  <small>Narthex</small>
          |</div>
          |</body>
          |</html>
         """.stripMargin
    )
  } getOrElse Logger.warn(s"No owner email for $dsInfo, so no notification was sent.")
}
