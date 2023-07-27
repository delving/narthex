package services

import init.NarthexConfig
import org.scalatest.flatspec._
import org.scalatest.matchers._
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.mailer.{Email, MailerClient}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any
import play.api.{Configuration, Environment}

import scala.jdk.CollectionConverters._

class MailServiceSpec extends AnyFlatSpec with should.Matchers with MockitoSugar {

  "mail" should "not be sent when no recipient where configured" in {
    val narthexConfig = new NarthexConfig(Configuration.load(Environment.simple()))
    val mailerMock = mock[MailerClient]
    val mailService = new PlayMailService(mailerMock, narthexConfig) (scala.concurrent.ExecutionContext.global)
    mailService.sendProcessingCompleteMessage("foo", "bar", "foobar")

    verifyNoInteractions(mailerMock)
  }

  "mail" should "be sent when some recipients where configured" in {
    val narthexConfig = new NarthexConfig(Configuration.load(Environment.simple(),
      Map("emailReportsTo" -> List("foo@bar.com").asJava)))
    val mailerMock = mock[MailerClient]
    val mailService = new PlayMailService(mailerMock, narthexConfig) (scala.concurrent.ExecutionContext.global)
    when(mailerMock.send(any[Email])).thenReturn("msgId")
    mailService.sendProcessingCompleteMessage("foo", "bar", "foobar")

    verify(mailerMock, times(1)).send(any[Email])
  }

}
