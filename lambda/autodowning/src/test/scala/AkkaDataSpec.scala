import models.{AkkaMember, AkkaMembersResponse, UriDecoder}
import org.specs2.mutable.Specification
import io.circe.generic.auto._

class AkkaDataSpec extends Specification with UriDecoder {
  "AkkaMembersResponse" should {
    "correctly represent the data from akka management http" in {
      val exampleData = """{
                          |  "selfNode": "akka.tcp://application@10.225.13.242:2552",
                          |  "oldestPerRole": {
                          |    "dc-default": "akka.tcp://application@10.225.12.113:2552"
                          |  },
                          |  "leader": "akka.tcp://application@10.225.13.242:2552",
                          |  "oldest": "akka.tcp://application@10.225.12.113:2552",
                          |  "unreachable": [
                          |    {
                          |      "node": "akka.tcp://application@10.225.12.113:2552",
                          |      "observedBy": [
                          |        "akka.tcp://application@10.225.12.134:2552",
                          |        "akka.tcp://application@10.225.13.242:2552",
                          |        "akka.tcp://application@10.225.14.100:2552",
                          |        "akka.tcp://application@10.225.14.115:2552"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.12.134:2552",
                          |      "observedBy": [
                          |        "akka.tcp://application@10.225.13.242:2552"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.13.5:2552",
                          |      "observedBy": [
                          |        "akka.tcp://application@10.225.12.134:2552",
                          |        "akka.tcp://application@10.225.13.242:2552",
                          |        "akka.tcp://application@10.225.14.100:2552",
                          |        "akka.tcp://application@10.225.14.115:2552"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.14.100:2552",
                          |      "observedBy": [
                          |        "akka.tcp://application@10.225.12.134:2552",
                          |        "akka.tcp://application@10.225.13.242:2552",
                          |        "akka.tcp://application@10.225.14.115:2552"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.14.115:2552",
                          |      "observedBy": [
                          |        "akka.tcp://application@10.225.12.134:2552",
                          |        "akka.tcp://application@10.225.13.242:2552"
                          |      ]
                          |    }
                          |  ],
                          |  "members": [
                          |    {
                          |      "node": "akka.tcp://application@10.225.13.5:2552",
                          |      "nodeUid": "-446939890",
                          |      "status": "Up",
                          |      "roles": [
                          |        "dc-default"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.13.242:2552",
                          |      "nodeUid": "1567074387",
                          |      "status": "WeaklyUp",
                          |      "roles": [
                          |        "dc-default"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.14.115:2552",
                          |      "nodeUid": "936327131",
                          |      "status": "Up",
                          |      "roles": [
                          |        "dc-default"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.12.134:2552",
                          |      "nodeUid": "-1598505790",
                          |      "status": "WeaklyUp",
                          |      "roles": [
                          |        "dc-default"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.14.100:2552",
                          |      "nodeUid": "1584237984",
                          |      "status": "Up",
                          |      "roles": [
                          |        "dc-default"
                          |      ]
                          |    },
                          |    {
                          |      "node": "akka.tcp://application@10.225.12.113:2552",
                          |      "nodeUid": "-1792809571",
                          |      "status": "Up",
                          |      "roles": [
                          |        "dc-default"
                          |      ]
                          |    }
                          |  ]
                          |}""".stripMargin

      val result = io.circe.parser.parse(exampleData).flatMap(_.as[AkkaMembersResponse])

      result must beRight
      val data  = result.right.get
      data.members.length mustEqual 6
      data.members.foreach(entry=>{
        println(entry.node.getScheme)
        println(entry.node.getHost)
        println(entry.node.getUserInfo)
        println(entry.node.getPort)
      })
      data.unreachable.length mustEqual 5
      data.selfNode mustEqual "akka.tcp://application@10.225.13.242:2552"
    }
  }
}
