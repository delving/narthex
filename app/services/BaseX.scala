package services

import org.basex.core.cmd._
import org.basex.server.ClientSession
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

/**
 * Minimal connection with BaseX, wrapping the Java API
 *
 * note: sessions are not reused yet
 *
 * @author Gerald de Jong <gerald@delving.eu>
 */

object BaseX {

  lazy val baseX: BaseX = new BaseX("localhost", 1984, "admin", "admin")

  def withSession[T](block: ClientSession => T): T = baseX.withSession(block)

  def withDbSession[T](database: String)(block: ClientSession => T): T = baseX.withDbSession(database)(block)

  def createDatabase(name: String) = withSession(_.execute(new CreateDB(name)))

  def createDatabase(name: String, content: String) = withSession(_.execute(new CreateDB(name, content)))

  def checkDatabase(name: String) = withSession(_.execute(new Check(name)))

  def dropDatabase(name: String) = withSession(_.execute(new DropDB(name)))

}

class BaseX(host: String, port: Int, user: String, pass: String) {

  def withSession[T](block: ClientSession => T): T = {
    val session = new ClientSession(host, port, user, pass)
    try {
      block(session)
    }
    finally {
      session.close()
    }
  }

  def withDbSession[T](database: String)(block: ClientSession => T): T = {
    withSession {
      session =>
        session.execute(new Open(database))
        session.execute(new Set("autoflush", "false"))
        val result = block(session)
        session.execute(new Flush())
        result
    }
  }
}

trait BaseXTools {

  val XSD_FORMATTER = ISODateTimeFormat.dateTime()
  val UTC_FORMATTER = ISODateTimeFormat.dateOptionalTimeParser()

  def toXSDString(dateTime: DateTime) = XSD_FORMATTER.print(dateTime)

  def fromXSDDateTime(dateString: String) = XSD_FORMATTER.parseDateTime(dateString)

  def fromUTCDateTime(dateString: String) = UTC_FORMATTER.parseDateTime(dateString)

  def quote(value: String) = {
    value match {
      case "" => "''"
      case string =>
        "'" + string.replace("'", "\'\'") + "'"
    }
  }
}