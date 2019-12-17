package services.FileMove

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{DocId, Indexer, ProxyLocationDAO}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ItemNotFound

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * verify that the source file is registered with us, and collect the IDs of all proxies for it.
  * @param indexer [[Indexer]] DAO providing access to the index data
  * @param proxyLocationDAO [[ProxyLocationDAO]] providing access to the proxy location data
  * @param esClient implicitly provided Elastic4s HttpClient
  * @param dynamoClient implicitly provided Alpakka DynamoClient
  */
class VerifySource(indexer:Indexer, proxyLocationDAO:ProxyLocationDAO)(implicit esClient:HttpClient, dynamoClient:DynamoClient) extends GenericMoveActor with DocId {
  import GenericMoveActor._

  override def receive: Receive = {
    case PerformStep(currentState)=>
      val originalSender = sender()

      indexer.getByIdFull(currentState.sourceFileId).flatMap({
        case Left(ItemNotFound(_))=>
          logger.warn(s"Requested file id ${currentState.sourceFileId} does not exist")
          originalSender ! StepFailed(currentState, s"Requested file id ${currentState.sourceFileId} does not exist")
          Future( () )
        case Left(err)=>
          originalSender ! StepFailed(currentState, err.toString)
          Future( () )
        case Right(entry)=>
          proxyLocationDAO.getAllProxiesFor(currentState.sourceFileId).map(results=>{
            val failures = results.collect({case Left(err)=>err})
            if(failures.nonEmpty){
              originalSender ! StepFailed(currentState, failures.map(_.toString).mkString(","))
            } else {
              val proxyList = results.collect({ case Right(proxyLoc) => proxyLoc })
              originalSender ! StepSucceeded(currentState.copy(entry = Some(entry), sourceFileProxies = Some(proxyList)))
            }
          })
      }).recover({
        case err:Throwable=>
          logger.error(s"Could not look up media source from id '${currentState.sourceFileId}': ", err)
          originalSender ! StepFailed(currentState, err.getMessage)
      })

    case RollbackStep(currentState)=>
      //nothing to roll back here
      logger.info("VerifySource has nothing to roll back")
      sender() ! StepSucceeded(currentState)
  }
}
