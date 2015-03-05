package specs

import org.joda.time.DateTime
import org.scalatestplus.play._
import play.api.libs.mailer.{Email, MailerPlugin}

class TestEMail extends PlaySpec with OneAppPerSuite with FakeTripleStore {

  // configuration:
  //  smtp = {
  //    host = "mx2.hostice.net"
  //
  //    //  smtp.port (defaults to 25)
  //    //  smtp.ssl (defaults to no)
  //    //  smtp.tls (defaults to no)
  //    //  smtp.user (optional)
  //    //  smtp.password (optional)
  //    //  smtp.debug (defaults to no, to take effect you also need to set the log level to "DEBUG" for the application logger)
  //    //  smtp.mock (defaults to no, will only log all the email properties instead of sending an email)
  //    //  smtp.timeout (defaults to 60s)
  //    //  smtp.connectiontimeout (defaults to 60s)
  //
  //  }

  "An email should be sent" in {

    MailerPlugin.send(Email(
      subject = "specs.TestEMail",
      from = "Narthex <narthex@delving.eu>",
      to = Seq("Gerald <gerald@delving.eu>"),
      attachments = Seq.empty,
      bodyHtml = Some(s"<html><body>The time is now ${new DateTime()}</body></html>")
    ))

  }

}
