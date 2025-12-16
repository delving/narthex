package init

import javax.inject._
import java.io.File
import scala.jdk.CollectionConverters._
import play.api.Configuration
import play.api.Logging

@Singleton
class NarthexConfig @Inject() (configuration: Configuration) extends Logging {

  def config: Configuration = configuration

  def harvestTimeOut: Long =
    configuration.getOptional[Long]("harvest.timeout").getOrElse(3L * 60 * 1000)
  logger.info(s"harvest.timeout: $harvestTimeOut")

  def useBulkApi: Boolean = true
  logger.info(s"useBulkApi: $useBulkApi")

  def rdfBaseUrl: String = configStringNoSlash("rdfBaseUrl")
  def nxUriPrefix: String = s"$rdfBaseUrl/resource"
  logger.info(s"rdfBaseUrl: $rdfBaseUrl")

  def naveApiUrl: String = configStringNoSlash("naveApiUrl")
  logger.info(s"naveApiUrl: $naveApiUrl")

  def naveApiAuthToken: String = configStringNoSlash("naveAuthToken")
  logger.info(s"naveApiAuthToken: $naveApiAuthToken")

  def mockBulkApi: Boolean =
    configuration.getOptional[Boolean]("mockBulkApi").getOrElse(false)
  logger.info(s"mockBulkApi: $mockBulkApi")

  private val homeDir: String = configuration
    .getOptional[String]("narthexHome")
    .getOrElse(System.getProperty("user.home") + "/NarthexFiles")

  private val dataDir: File = new File(homeDir)
  if (!dataDir.canWrite) {
    throw new RuntimeException(s"Configured $dataDir is not writeable")
  }

  def narthexDataDir: File = dataDir
  logger.info(s"narthexHome: $dataDir")

  def orgId: String = configString("orgId")
  logger.info(s"orgId: $orgId")

  def narthexDomain: String = configStringNoSlash("domains.narthex")
  logger.info(s"domains.narthex: $narthexDomain")

  def naveDomain: String = configStringNoSlash("domains.nave")
  logger.info(s"domains.nave: $naveDomain")

  def sipAppDownloadUrl: String = configString("sipAppDownloadUrl")
  logger.info(s"sipAppDownloadUrl: $sipAppDownloadUrl")

  def enableIncrementalHarvest: Boolean = configuration
    .getOptional[Boolean]("enableIncrementalHarvest")
    .getOrElse(false)
  logger.info(s"enableIncrementalHarvest: $enableIncrementalHarvest")

  def enableDefaultMappings: Boolean = configuration
    .getOptional[Boolean]("enableDefaultMappings")
    .getOrElse(false)
  logger.info(s"enableDefaultMappings: $enableDefaultMappings")

  def enableDatasetDiscovery: Boolean = configuration
    .getOptional[Boolean]("enableDatasetDiscovery")
    .getOrElse(false)
  logger.info(s"enableDatasetDiscovery: $enableDatasetDiscovery")

  def harvestRetryIntervalMinutes: Int = configuration
    .getOptional[Int]("narthex.harvest.retryIntervalMinutes")
    .getOrElse(60)
  logger.info(s"narthex.harvest.retryIntervalMinutes: $harvestRetryIntervalMinutes")

  def harvestMaxRetries: Int = configuration
    .getOptional[Int]("narthex.harvest.maxRetries")
    .getOrElse(40)
  logger.info(s"narthex.harvest.maxRetries: $harvestMaxRetries")

  def crunchWhiteSpace: Boolean =
    configuration.getOptional[Boolean]("crunchWhiteSpace").getOrElse(true)
  logger.info(s"crunchWhiteSpace: $crunchWhiteSpace")

  def xsdValidation: Boolean = configFlag("xsd-validation")
  logger.info(s"xsd-validation: $xsdValidation")

  def concurrencyLimit: Int =
    configuration.getOptional[Int]("concurrencyLimit").getOrElse(3)
  logger.info(s"concurrencyLimit: $concurrencyLimit")

  def sessionTimeoutInSeconds: Int = configuration
    .getOptional[Int]("sessionTimeoutInSeconds")
    .getOrElse(60 * 60 * 4)
  logger.info(s"sessionTimeoutInSeconds: $sessionTimeoutInSeconds")

  private val allDatasetTypes =
    Set("narthex", "rdf", "nt", "photo", "ead", "fragmentsOnly")
  private val datasetTypes: List[String] = configuration
    .getOptional[Seq[String]]("datasetTypes")
    .map(_.toList)
    .getOrElse(List())
  datasetTypes.foreach(datasetType => {
    if (!allDatasetTypes(datasetType)) {
      throw new IllegalStateException(s"Unsupported dataset type: $datasetType")
    }
  })

  def supportedDatasetTypes: List[String] = datasetTypes
  logger.info(s"datasetTypes: ${supportedDatasetTypes.asJava}")

  def emailReportsTo: List[String] = configuration
    .getOptional[Seq[String]]("emailReportsTo")
    .map(_.toList)
    .getOrElse(List())
  logger.info(s"emailReportsTo: ${emailReportsTo.asJava}")

  def tripleStoreUrl: String = configStringNoSlash("triple-store")
  logger.info(s"triple-store: $tripleStoreUrl")
  def tripleStoreLog = configFlag("triple-store-log")
  logger.info(s"triple-store-log: $tripleStoreLog")
  def sparqlQueryPath: String = configStringNoSlash("sparql-query-path")
  logger.info(s"sparql-query-path: $sparqlQueryPath")
  def sparqlUpdatePath: String = configStringNoSlash("sparql-update-path")
  logger.info(s"sparql-update-path: $sparqlUpdatePath")
  def graphStorePath: String = configStringNoSlash("graph-store-path")
  logger.info(s"graph-store-path: $graphStorePath")
  def graphStoreParam: String = configStringNoSlash("graph-store-param")
  logger.info(s"graph-store-param: $graphStoreParam")

  // Fuseki connection pool settings
  def fusekiMaxConnectionsPerHost: Int = configuration.getOptional[Int]("fuseki.maxConnectionsPerHost").getOrElse(10)
  logger.info(s"fuseki.maxConnectionsPerHost: $fusekiMaxConnectionsPerHost")
  def fusekiMaxConnectionsTotal: Int = configuration.getOptional[Int]("fuseki.maxConnectionsTotal").getOrElse(30)
  logger.info(s"fuseki.maxConnectionsTotal: $fusekiMaxConnectionsTotal")
  def fusekiConnectionTimeoutMs: Int = configuration.getOptional[Int]("fuseki.connectionTimeoutMs").getOrElse(5000)
  logger.info(s"fuseki.connectionTimeoutMs: $fusekiConnectionTimeoutMs")
  def fusekiRequestTimeoutMs: Int = configuration.getOptional[Int]("fuseki.requestTimeoutMs").getOrElse(30000)
  logger.info(s"fuseki.requestTimeoutMs: $fusekiRequestTimeoutMs")
  def fusekiReadTimeoutMs: Int = configuration.getOptional[Int]("fuseki.readTimeoutMs").getOrElse(60000)
  logger.info(s"fuseki.readTimeoutMs: $fusekiReadTimeoutMs")

  // Fuseki authentication settings
  def fusekiUsername: Option[String] = configuration.getOptional[String]("fuseki-username").filter(_.nonEmpty)
  def fusekiPassword: Option[String] = configuration.getOptional[String]("fuseki-password").filter(_.nonEmpty)
  logger.info(s"fuseki authentication enabled: ${fusekiUsername.isDefined}")

  private def configFlag(name: String): Boolean =
    configuration.getOptional[Boolean](name).getOrElse(false)

  private def configString(name: String) = configuration
    .getOptional[String](name)
    .getOrElse(throw new RuntimeException(s"Missing config string: $name"))

  private def configLong(name: String) = configuration
    .getOptional[Long](name)
    .getOrElse(
      throw new RuntimeException(s"Missing/invalid config string: $name")
    )

  private def configStringNoSlash(name: String) =
    configString(name).replaceAll("\\/$", "")

}
