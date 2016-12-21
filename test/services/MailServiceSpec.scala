package services

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.mock.MockitoSugar
import play.api.libs.mailer.{Email, MailerClient}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any

class MailServiceSpec extends FlatSpec with Matchers with MockitoSugar {

  "mail" should "not be sent when no recipient where configured" in {
    val mailerMock = mock[MailerClient]
    val mailService = new PlayMailService(mailerMock, List()) (scala.concurrent.ExecutionContext.global)
    mailService.sendProcessingCompleteMessage("foo", "bar", "foobar")

    verifyZeroInteractions(mailerMock)
  }

  "mail" should "be sent when some recipients where configured" in {
    val mailerMock = mock[MailerClient]
    val mailService = new PlayMailService(mailerMock, List("foo@bar.com")) (scala.concurrent.ExecutionContext.global)
    when(mailerMock.send(any[Email])).thenReturn("msgId")
    mailService.sendProcessingCompleteMessage("foo", "bar", "foobar")

    verify(mailerMock, times(1)).send(any[Email])
  }
}
