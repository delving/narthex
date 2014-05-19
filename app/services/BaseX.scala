package services

import org.basex.BaseXServer
import java.io.{OutputStream, ByteArrayInputStream, File}
import org.basex.server.{ServerCmd, ClientQuery, ClientSession}
import org.basex.core.cmd.{Flush, Delete, Rename}
import scala.xml.{XML, Node}
import org.basex.io.in.{DecodingInput, BufferInput}
import org.basex.util.Token
import org.basex.util.list.ByteList

/**
 * BaseX Client wrapper for Scala
 *
 * Absconded from https://github.com/delving/basex-scala-client
 *
 * TODO support remote connection
 * TODO query with limits
 *
 * @author Manuel Bernhardt <bernhardt.manuel@gmail.com>
 */

class BaseX(host: String, port: Int, eport: Int, user: String, pass: String, useQueryCache: Boolean = false) {

  private var server: BaseXServer = null

  /**
   * Starts an embedded BaseX server
   * @param dataDirectory the data directory on disk. Leave empty to use BaseX default.
   */
  def start(dataDirectory: File) {
    if (!dataDirectory.exists()) {
      if (!dataDirectory.mkdirs()) throw new RuntimeException("Failed to create data directory for BaseX " + dataDirectory)
    }
    System.setProperty("org.basex.path", dataDirectory.getAbsolutePath)
    server = new BaseXServer(s"-e$eport", s"-p$port")
  }

  /**
   * Stops an embedded BaseX server
   */
  def stop() {
    BaseXServer.stop(port, eport)
  }

  /**
   * Starts a new session for the specified database
   * @param database the name of the database to connect to
   * @param block the code to run in the context of the session
   */
  def withSession[T](database: String)(block: ClientSession => T): T = {
    withSession {
      session =>
        session.execute("open " + database)
        block(session)
    }
  }


  /**
   * Executes code in the context of a session, provided a database is already open.
   * Depending on the configuration, may use a [[StreamingClientSession]]
   * @param block the code to run in the context of the session
   */
  def withSession[T](block: ClientSession => T): T = {
    val session = if (useQueryCache) {
      new ClientSession(host, port, user, pass)
    } else {
      new StreamingClientSession(host, port, user, pass)
    }
    try {
      block(session)
    } finally {
      session.close()
    }
  }

  /**
   * Executes code in the context of specific query results
   * @param database the name of the database
   * @param query the query to execute
   * @param block the code to execute in the context of the query results
   */
  def withQuery[T](database: String, query: String)(block: RichClientQuery => T) = {
    withSession {
      session =>
        session.execute("open " + database)
        val q = session.query(query)
        try {
          block(q)
        } finally {
          q.close()
        }

    }
  }

  def createDatabase(name: String) {
    withSession {
      session => session.execute("create db " + name)
    }
  }

  def openDatabase(name: String) {
    withSession {
      session => session.execute("open " + name)
    }
  }


  def dropDatabase(name: String) {
    withSession {
      session => session.execute("drop db " + name)
    }
  }

  def alter(db: String, newDb: String) {
    withSession {
      session => session.execute("alter db %s %s".format(db, newDb))
    }
  }

  def add(database: String, path: String, document: String) {
    withSession {
      session =>
        session.execute("open " + database)
        session.add(path, new ByteArrayInputStream(document.getBytes("utf-8")))
    }
  }

  def replace(database: String, path: String, document: String) {
    withSession {
      session =>
        session.execute("open " + database)
        session.replace(path, new ByteArrayInputStream(document.getBytes("utf-8")))
    }
  }

  def rename(database: String, path: String, newPath: String) {
    withSession {
      session =>
        session.execute("open " + database)
        session.execute(new Rename(path, newPath))
    }
  }

  def delete(database: String, path: String) {
    withSession {
      session =>
        session.execute("open " + database)
        session.execute(new Delete(path))
    }
  }

  def query(database: String, query: String): List[String] = {
    withSession {
      session =>
        session.execute("open " + database)
        val q = session.query(query)
        val r = q.toList
        q.close()
        r
    }
  }

  def fetchRaw(database: String, path: String): Option[String] = {
    withSession {
      session => session.query( """db:open("%s", "%s")""".format(database, path)).toList.headOption
    }
  }

  def fetch(database: String, path: String): Option[Node] = fetchRaw(database, path).map(scala.xml.XML.loadString)

  class RichClientQuery(query: ClientQuery) extends Iterator[String] {

    def next(): String = {
      query match {
        case q: NonCachedClientQuery =>
          q.getNext
        case _ =>
          query.next
      }
    }

    def hasNext: Boolean = query.more()

  }

  class RichClientSession(session: ClientSession) {

    def open(db: String) {
      session.execute("open " + db)
    }

    def find(query: String): Iterator[Node] = {
      session.query(query).map(XML.loadString)
    }

    def findRaw(query: String): Iterator[String] = {
      session.query(query)
    }

    def findOne(query: String): Option[Node] = {
      findOneRaw(query).map(XML.loadString).toList.headOption
    }

    def findOneRaw(query: String): Option[String] = {
      session.query(query).toList.headOption
    }

    def setAutoflush(flush: Boolean) {
      if (flush) {
        session.execute("set autoflush true")
      } else {
        session.execute("set autoflush false")
      }
    }

    def flush() {
      session.execute(new Flush())
    }

    def createAttributeIndex() {
      session.execute("create index attribute")
    }

  }

  implicit def withRichClientQuery[A <: ClientQuery](query: A): RichClientQuery = new RichClientQuery(query)

  implicit def withRichClientSession[A <: ClientSession](session: A): RichClientSession = new RichClientSession(session)

  class StreamingClientSession(val host: String, val port: Int, val user: String, val pass: String) extends ClientSession(host, port, user, pass) {

    def getServerOutput = sout

    def getServerInput = sin

    override def send(s: String) {
      super.send(s)
    }

    override def query(query: String): ClientQuery = new NonCachedClientQuery(query, this, out)
  }

  class NonCachedClientQuery(query: String, session: StreamingClientSession, os: OutputStream) extends ClientQuery(query, session, os) {

    private def ncs = cs.asInstanceOf[StreamingClientSession]

    private var streamConsumed: Boolean = false
    private var resultStream: BufferInput = null

    private var lookAhead: Array[Byte] = null

    override def more(): Boolean = {
      initStream()
      !streamConsumed && lookAhead != null
    }

    def getNext = {
      if (more()) {
        val l = lookAhead
        lookAhead = fetchNext()
        Token.string(l)
      } else {
        null
      }
    }

    def fetchNext(): Array[Byte] = {
      if (streamConsumed) return null
      if (!(resultStream.read > 0)) {
        streamConsumed = true
        null
      } else {
        val bl = new ByteList()
        val di: DecodingInput = new DecodingInput(resultStream)
        var b: Int = 0
        while ( {
          b = di.read
          b
        } != -1) bl.add(b)
        pos += 1
        bl.toArray
      }
    }

    def initStream() {
      if (resultStream == null) {
        ncs.getServerOutput.write(ServerCmd.ITER.code)
        ncs.send(id)
        ncs.getServerOutput.flush()
        resultStream = new BufferInput(ncs.getServerInput)
        lookAhead = fetchNext()
      }
    }
  }

}
