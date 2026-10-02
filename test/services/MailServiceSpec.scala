package services

import init.NarthexConfig
import org.scalatest.flatspec._
import org.scalatest.matchers._
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.mailer.{Email, MailerClient}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentCaptor
import play.api.{Configuration, Environment}

import scala.jdk.CollectionConverters._

class MailServiceSpec extends AnyFlatSpec with should.Matchers with MockitoSugar {

  private def service(recipients: List[String], mailerMock: MailerClient): PlayMailService = {
    val config =
      if (recipients.isEmpty) new NarthexConfig(Configuration.load(Environment.simple()))
      else new NarthexConfig(Configuration.load(Environment.simple(),
        Map("emailReportsTo" -> recipients.asJava)))
    new PlayMailService(mailerMock, config)(scala.concurrent.ExecutionContext.global)
  }

  "mail" should "not be sent when no recipient where configured" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(Nil, mailerMock)
    mailService.sendProcessingErrorMessage("foo", "bar", None)
    mailService.flushPending()

    verifyNoInteractions(mailerMock)
  }

  "mail" should "be sent when some recipients where configured" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    when(mailerMock.send(any[Email])).thenReturn("msgId")
    mailService.sendProcessingErrorMessage("foo", "bar", None)
    mailService.flushPending()

    verify(mailerMock, times(1)).send(any[Email])
  }

  it should "not send anything before the flush" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    mailService.sendProcessingErrorMessage("foo", "bar", None)

    verifyNoInteractions(mailerMock)
  }

  it should "send nothing when there are no failures to report" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    mailService.flushPending()

    verifyNoInteractions(mailerMock)
  }

  it should "collapse repeated failures of one dataset into a single mail" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    when(mailerMock.send(any[Email])).thenReturn("msgId")

    // What a harvest with retries produces: the same dataset, over and over.
    (1 to 40).foreach(_ => mailService.sendProcessingErrorMessage("foo", "Forbidden", None))
    mailService.flushPending()

    val captor = ArgumentCaptor.forClass(classOf[Email])
    verify(mailerMock, times(1)).send(captor.capture())
    captor.getValue.subject should be("Failure in dataset: foo (40x)")
  }

  it should "group failures of several datasets into one digest" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    when(mailerMock.send(any[Email])).thenReturn("msgId")

    mailService.sendProcessingErrorMessage("foo", "Forbidden", None)
    mailService.sendProcessingErrorMessage("bar", "Forbidden", None)
    mailService.sendProcessingErrorMessage("foo", "Forbidden", None)
    mailService.flushPending()

    val captor = ArgumentCaptor.forClass(classOf[Email])
    verify(mailerMock, times(1)).send(captor.capture())
    captor.getValue.subject should be("Narthex: 2 datasets met fouten")
    val body = captor.getValue.bodyHtml.getOrElse("")
    body should include("foo")
    body should include("bar")
    body should include("3 mislukkingen")
  }

  it should "list only the first datasets when there are too many" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    when(mailerMock.send(any[Email])).thenReturn("msgId")

    val total = PlayMailService.MaxDatasetsListed + 7
    (1 to total).foreach(i => mailService.sendProcessingErrorMessage(s"spec-$i", "Forbidden", None))
    mailService.flushPending()

    val captor = ArgumentCaptor.forClass(classOf[Email])
    verify(mailerMock, times(1)).send(captor.capture())
    captor.getValue.subject should be(s"Narthex: $total datasets met fouten")
    val body = captor.getValue.bodyHtml.getOrElse("")
    body should include(s"$total datasets met fouten")
    body should include("En nog 7 datasets")
    body should include("spec-1")
    body should not include "spec-57"
  }

  it should "start empty again after a flush" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    when(mailerMock.send(any[Email])).thenReturn("msgId")

    mailService.sendProcessingErrorMessage("foo", "bar", None)
    mailService.flushPending()
    mailService.flushPending()

    verify(mailerMock, times(1)).send(any[Email])
  }

  it should "keep the newest message and the first exception" in {
    val mailerMock = mock[MailerClient]
    val mailService = service(List("foo@bar.com"), mailerMock)
    when(mailerMock.send(any[Email])).thenReturn("msgId")

    mailService.sendProcessingErrorMessage("foo", "first message", Some(new RuntimeException("first boom")))
    mailService.sendProcessingErrorMessage("foo", "latest message", Some(new RuntimeException("later boom")))
    mailService.flushPending()

    val captor = ArgumentCaptor.forClass(classOf[Email])
    verify(mailerMock, times(1)).send(captor.capture())
    val body = captor.getValue.bodyHtml.getOrElse("")
    body should include("latest message")
    body should include("first boom")
    body should not include "later boom"
  }

}
