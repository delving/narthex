package services

import java.sql.Connection
import javax.sql.DataSource
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import play.api.Logging

/** PostgreSQL connection configuration.
  *
  * @param url JDBC connection URL
  * @param user database user
  * @param password database password
  * @param maxPoolSize maximum number of connections in the HikariCP pool
  * @param minIdle minimum number of idle connections in the pool
  * @param connectionTimeout maximum time (ms) to wait for a connection from the pool
  * @param idleTimeout maximum time (ms) a connection may sit idle in the pool
  * @param maxLifetime maximum lifetime (ms) of a connection in the pool
  */
case class PostgresConfig(
    url: String,
    user: String,
    password: String,
    maxPoolSize: Int = 10,
    minIdle: Int = 2,
    connectionTimeout: Long = 30000,
    idleTimeout: Long = 600000,
    maxLifetime: Long = 1800000
)

/** Manages a PostgreSQL HikariCP connection pool and runs Flyway migrations on startup.
  *
  * Usage:
  * {{{
  *   val service = new DatabaseService(config)
  *   service.initialize()  // runs migrations, then opens pool
  *   val conn = service.getConnection()
  *   try { ... } finally { conn.close() }
  *   service.close()       // shuts down pool
  * }}}
  */
class DatabaseService(config: PostgresConfig) extends Logging {

  @volatile private var dataSource: Option[HikariDataSource] = None

  /** Run Flyway migrations and then create the HikariCP connection pool. */
  def initialize(): Unit = {
    logger.info(s"Running Flyway migrations against ${config.url}")
    val flyway = Flyway.configure()
      .dataSource(config.url, config.user, config.password)
      .locations("classpath:db/migration")
      .baselineOnMigrate(true)
      .baselineVersion("0")
      .load()
    val result = flyway.migrate()
    logger.info(
      s"Flyway migrations complete: ${result.migrationsExecuted} executed, schema version ${result.targetSchemaVersion}"
    )

    logger.info(s"Creating HikariCP connection pool (maxPoolSize=${config.maxPoolSize})")
    val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl(config.url)
    hikariConfig.setUsername(config.user)
    hikariConfig.setPassword(config.password)
    hikariConfig.setMaximumPoolSize(config.maxPoolSize)
    hikariConfig.setMinimumIdle(config.minIdle)
    hikariConfig.setConnectionTimeout(config.connectionTimeout)
    hikariConfig.setIdleTimeout(config.idleTimeout)
    hikariConfig.setMaxLifetime(config.maxLifetime)
    hikariConfig.setPoolName("narthex-postgres")

    dataSource = Some(new HikariDataSource(hikariConfig))
    logger.info("HikariCP connection pool created")
  }

  /** Borrow a connection from the pool. Caller MUST close the returned connection. */
  def getConnection(): Connection = dataSource match {
    case Some(ds) => ds.getConnection
    case None =>
      throw new IllegalStateException(
        "DatabaseService not initialized — call initialize() first"
      )
  }

  /** Return the underlying DataSource (e.g. for use by repository classes). */
  def getDataSource: DataSource = dataSource match {
    case Some(ds) => ds
    case None =>
      throw new IllegalStateException(
        "DatabaseService not initialized — call initialize() first"
      )
  }

  /** Quick health check: borrows a connection and validates it. */
  def isHealthy: Boolean =
    try {
      dataSource match {
        case Some(ds) =>
          val conn = ds.getConnection
          try conn.isValid(5)
          finally conn.close()
        case None => false
      }
    } catch {
      case _: Exception => false
    }

  /** Close the HikariCP connection pool. */
  def close(): Unit = {
    dataSource.foreach { ds =>
      logger.info("Closing HikariCP connection pool")
      ds.close()
    }
    dataSource = None
  }
}

/** Global singleton holder for the DatabaseService, following the same pattern
  * as [[GlobalWorkflowDatabase]].
  *
  * Will be replaced by proper Guice DI in Phase 3.
  */
object GlobalDatabaseService {
  @volatile private var instance: Option[DatabaseService] = None

  def set(service: DatabaseService): Unit = {
    instance = Some(service)
  }

  def get(): Option[DatabaseService] = instance

  def getOrThrow(): DatabaseService = instance.getOrElse(
    throw new IllegalStateException("PostgreSQL not configured")
  )

  def close(): Unit = {
    instance.foreach(_.close())
    instance = None
  }
}
