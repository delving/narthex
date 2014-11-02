package services

import org.basex.core.cmd._
import org.basex.server.ClientSession

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

  def quote(value: String) = {
    value match {
      case "" => "''"
      case string =>
        "'" + string.replace("'", "\'\'") + "'"
    }
  }
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
