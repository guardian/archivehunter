package services

import akka.actor.{Actor, ActorSystem}
import akka.stream.scaladsl.Keep
import akka.stream.{ActorMaterializer, KillSwitches}
import com.google.inject.Injector
import helpers._
import javax.inject.Inject
import models.{ScanTarget, ScanTargetDAO}
import play.api.{Configuration, Logger}

object LegacyProxiesScanner {
  case class ScanBucket(tgt: ScanTarget)
}

class LegacyProxiesScanner @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager, s3ClientMgr:S3ClientManager,
                                     esClientMgr:ESClientManager, scanTargetDAO: ScanTargetDAO, injector:Injector)(implicit system:ActorSystem)extends Actor {
  import LegacyProxiesScanner._
  private val logger = Logger(getClass)
  implicit val mat = ActorMaterializer.create(system)

  override def receive: Receive = {
    case ScanBucket(tgt)=>
      logger.info(s"Starting proxy scan on $tgt")
      val client = s3ClientMgr.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))

      val keySource = client.listBucket(tgt.proxyBucket, None)
      val ddbSink = injector.getInstance(classOf[DDBSink])
      val converter = new S3ToProxyLocationFlow(s3ClientMgr,config, tgt.bucketName)

      keySource.via(converter).log("legacy-proxies-scanner").to(ddbSink).run()
  }

}
