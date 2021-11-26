package services.datamigration.streamcomponents

import akka.Done
import akka.stream.scaladsl.GraphDSL
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import com.amazonaws.services.dynamodbv2.model.{BatchWriteItemRequest, BatchWriteItemResult, WriteRequest}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import org.slf4j.LoggerFactory
import play.api.Configuration

import java.util
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

class LightboxUpdateSink (tableName:String, config:Configuration, dynamoClientManager: DynamoClientManager, maxAttempts:Int=100) extends GraphStageWithMaterializedValue[SinkShape[UpdateRequest], Future[Done]] {
  private val logger = LoggerFactory.getLogger(getClass)
  private final val in:Inlet[UpdateRequest] = Inlet.create("LightboxUpdateSink.in")

  override def shape: SinkShape[UpdateRequest] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val completionPromise = Promise[Done]()

    val logic = new GraphStageLogic(shape) {
      val client = dynamoClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))
      private var ops:Seq[WriteRequest] = Seq()

      def shouldCommit = ops.length >=24

      def commit = Try {
        logger.info(s"Committing ${ops.length} operations to $tableName")
        val rq = new BatchWriteItemRequest()
          .withRequestItems(Map(tableName->ops.asJava).asJava)
        client.batchWriteItem(rq)
      }

      def commitWithBackoff(attempt:Int=0):Try[BatchWriteItemResult] = {
        commit match {
          case Success(result)=>
            val unprocessedCount = result.getUnprocessedItems.size()
            val delaySeconds = 2*attempt

            if(unprocessedCount>0) {
              logger.warn(s"There were $unprocessedCount items left unprocessed after the batch, retrying after $delaySeconds seconds")
              Thread.sleep(delaySeconds*1000)
              ops = result.getUnprocessedItems.get(tableName).asScala
              if(attempt>=maxAttempts) {
                Failure(new RuntimeException(s"Could not commit $unprocessedCount records after $attempt attempts"))
              } else {
                commitWithBackoff(attempt + 1)
              }
            } else {
              logger.info(s"All operations committed")
              ops = List()
              Success(result)
            }
          case Failure(err)=>
            logger.warn(s"Could not commit operations: ${err.getMessage}")
            Failure(err)
        }
      }

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          ops = ops ++ elem.marshal

          if(shouldCommit) {
            commitWithBackoff() match {
              case Success(_)=>
                logger.info(s"Committed all operations")
                pull(in)
              case Failure(err)=>
                logger.error(s"DynamoDB error, could not write data: ${err.getMessage}", err)
                completionPromise.failure(err)
                failStage(err)
            }
          } else {
            logger.debug(s"Queued items for writing, queue length is now ${ops.length}")
            pull(in)
          }
        }

        override def onUpstreamFinish(): Unit = {
          logger.info(s"Upstream completed, ${ops.length} items outstanding")

          if(ops.nonEmpty) commitWithBackoff() match {
            case Success(_)=>
              logger.info(s"Committed successfully")
              completionPromise.success(Done)
              completeStage()
            case Failure(err)=>
              logger.error(s"Outstanding commit failed: ${err.getMessage}", err)
              completionPromise.failure(err)
              failStage(err)
          } else {
            //nothing to commit
            completionPromise.success(Done)
            completeStage()
          }
        }
      })

      override def preStart(): Unit = {
        pull(in)
      }
    }

    (logic, completionPromise.future)
  }
}

object LightboxUpdateSink {
  def apply(tableName:String, config:Configuration, dynamoClientManager: DynamoClientManager, maxAttempts:Int=100) = {
    val fact = new LightboxUpdateSink(tableName, config, dynamoClientManager, maxAttempts)
    GraphDSL.create(fact) { builder=> sink=>
      SinkShape.of(sink.in)
    }
  }
}