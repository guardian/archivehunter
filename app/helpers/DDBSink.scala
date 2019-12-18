package helpers

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.{ProxyLocation, ProxyLocationEncoder}
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

final class DDBSink @Inject()(clientMgr: DynamoClientManager,config:Configuration)(implicit system:ActorSystem)
  extends GraphStage[SinkShape[ProxyLocation]] with ProxyLocationEncoder {
  private val in:Inlet[ProxyLocation] = Inlet.create("DDBSink.in")

  override def shape: SinkShape[ProxyLocation] = SinkShape.of(in)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      private val logger = Logger(getClass)
      var recordBuffer:Seq[ProxyLocation] = Seq()

      implicit val mat = ActorMaterializer.create(system)
      val flushFrequency = config.getOptional[Int]("dynamodb.flushFrequency").getOrElse(100)
      val client = clientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))
      val tableName = config.getOptional[String]("proxies.tableName").getOrElse("archiveHunterProxies")

      val table = Table[ProxyLocation](tableName)

      /**
        * if the provided Set of items contains duplicate database primary keys (from the perspective of Dynamo) then it fails.
        * this function removes any duplicates of these keys so that we know that the update will succeed
        * @return Iterable of unique ProxyLocation objects
        */
      def dedupeRecordBuffer:Iterable[ProxyLocation] = {
        val recordBufferMap = recordBuffer.map(loc=>(loc.fileId,loc.proxyType)->loc).toMap
        recordBufferMap.values
      }

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit ={
          val elem=grab(in)

          recordBuffer++=Seq(elem)

          if(recordBuffer.length>=flushFrequency){
            val dduped = dedupeRecordBuffer.toSeq
            logger.debug(s"Flushing ${dduped.length} records...")
            val r = Await.result(ScanamoAlpakka.exec(client)(table.putAll(dduped.toSet)), 1 minute)
            logger.debug(s"Flush completed")
            recordBuffer = Seq()
          } else {
            logger.debug("Buffering record")
          }
          pull(in)
        }
      })

      override def preStart() = {
        pull(in)  //start off the chain...
      }

      override def postStop(): Unit = {
        val dduped = dedupeRecordBuffer.toSeq
        logger.info(s"Stream ended. Flushing ${dduped.length} remaining records...")
        val r = Await.result(ScanamoAlpakka.exec(client)(table.putAll(dduped.toSet)), 1 minute)
        logger.debug(s"Flush completed")
        recordBuffer = Seq()
      }
    }
}
