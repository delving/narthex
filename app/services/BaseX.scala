package services

import org.basex.core.BaseXException
import org.basex.core.cmd._
import org.basex.server.ClientSession
import play.api.Logger

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

  def withDbSession[T](name: String, documentOpt: Option[String] = None)(block: ClientSession => T): T = {
    try {
      baseX.withDbSession[T](name)(block)
    }
    catch {
      case be: BaseXException =>
        if (be.getMessage.contains("not found")) {
          baseX.withSession { session =>
            Logger.info(s"Creating database $name containing $documentOpt")
            val create = documentOpt.map(document => new CreateDB(name, s"<$document/>")).getOrElse(new CreateDB(name))
            session.execute(create)
          }
          baseX.withDbSession(name)(block)
        }
        else {
          throw be
        }
    }
  }

  def createCleanDatabase(name: String) = baseX.withSession(_.execute(new CreateDB(name)))

  def dropDatabase(name: String) = baseX.withSession(_.execute(new DropDB(name)))

  def quote(value: String) = value match {
    case "" => "''"
    case string =>
      "'" + string.replace("'", "\'\'") + "'"
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

  def withDbSession[T](name: String)(block: ClientSession => T): T = withSession { session =>
    session.execute(new Open(name))
    session.execute(new Set("autoflush", "false"))
    val result = block(session)
    session.execute(new Flush())
    result
  }
}
