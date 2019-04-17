package services.FileMove

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{DocId, Indexer, ProxyLocationDAO}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ItemNotFound

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * verify that the source file is registered with us, and collect the IDs of all proxies for it.
  * @param indexer
  * @param proxyLocationDAO
  * @param esClient
  * @param dynamoClient
  */
class VerifySource(indexer:Indexer, proxyLocationDAO:ProxyLocationDAO)(implicit esClient:HttpClient, dynamoClient:DynamoClient) extends GenericMoveActor with DocId {
  import GenericMoveActor._

  override def receive: Receive = {
    case PerformStep(currentState)=>
      val originalSender = sender()

      indexer.getByIdFull(currentState.sourceFileId).map({
        case Left(ItemNotFound(_))=>
          logger.warn(s"Requested file id ${currentState.sourceFileId} does not exist")
          originalSender ! StepFailed(currentState, s"Requested file id ${currentState.sourceFileId} does not exist")
        case Left(err)=>
          originalSender ! StepFailed(currentState, err.toString)
        case Right(entry)=>
          proxyLocationDAO.getAllProxiesFor(currentState.sourceFileId).map(results=>{
            val failures = results.collect({case Left(err)=>err})
            if(failures.nonEmpty){
              originalSender ! StepFailed(currentState, failures.map(_.toString).mkString(","))
            }
            val proxyList = results.collect({case Right(proxyLoc)=>proxyLoc})
            originalSender ! StepSucceeded(currentState.copy(entry=Some(entry), sourceFileProxies = Some(proxyList)))
          })
      })

    case RollbackStep(currentState)=>
      //nothing to roll back here

  }
}
