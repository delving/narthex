package services

//class TestHarvesting(_system: ActorSystem) extends TestKit(_system)
//with ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with Harvesting with ScalaFutures {
//
//  val userHome = "/tmp/narthex-user"
//  var repo = new Repo(userHome, "test-harvesting")
//
//  def this() = this(ActorSystem("TestHarvesting"))
//
//  override def beforeAll() = {
//    deleteQuietly(new File(userHome))
//  }
//
//  override def afterAll() = {
//    TestKit.shutdownActorSystem(system)
//  }
//
//  "The AdLib Harvester" should "fetch pages" in {
//
//    val datasetRepo = repo.datasetRepo("umu-test__adlib.zip")
//    val harvester = system.actorOf(Props(new Harvester(datasetRepo)))
//
//    harvester ! HarvestAdLib(
//      url = "http://umu.adlibhosting.com/api/wwwopac.ashx",
//      database = "collect"
//    )
//
//    //    todo: find another way to terminate
//    //    expectMsg(15.minutes, HarvestComplete())
//  }
//
//  "The PMH Harvester" should "fetch pages" in {
//
//    val datasetRepo = repo.datasetRepo("pmh-test__oai_dc.zip")
//    val harvester = system.actorOf(Props(new Harvester(datasetRepo)))
//
//    harvester ! HarvestPMH(
//      url = "http://62.221.199.184:7829/oai",
//      set = "",
//      prefix = "oai_dc"
//    )
//
//    //    todo: find another way to terminate
//    //    expectMsg(10.minutes, HarvestComplete())
//  }
//}
