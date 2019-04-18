package services.FileMove

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{Indexer, ProxyLocation, ProxyLocationDAO}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * this step copies the ArchiveEntry record for the given file to a new ID, copies the Proxy records to the new ID and deletes the
  * old ones.
  * rollback makes it copy them back again the other way
  */
class UpdateIndexRecords(indexer:Indexer, proxyLocationDAO: ProxyLocationDAO)(implicit esClient:HttpClient, dynamoClient:DynamoClient) extends GenericMoveActor {
  import GenericMoveActor._

  def deleteCopiedProxies(proxyList:Seq[ProxyLocation]) = {
    val proxyDeleteFutureList = proxyList.map(loc=>proxyLocationDAO.deleteProxyRecord(loc.proxyId))

    Future.sequence(proxyDeleteFutureList)
  }

  override def receive: Receive = {
    case PerformStep(state)=>
      val originalSender = sender()
      if(state.sourceFileProxies.isEmpty || state.destFileProxy.isEmpty || state.destFileId.isEmpty){
        sender() ! StepFailed(state, "Not enough state elements were defined")
      } else {
        indexer.getById(state.sourceFileId).map(entry => {
          logger.debug(s"Looked up entry: $entry")
          val updatedEntry = entry.copy(id = state.destFileId.get)
          indexer.indexSingleItem(updatedEntry).map({
            case Success(_) =>
              logger.debug(s"Saved updated etnry")
              val proxyUpdateFutureList = state.destFileProxy.get.map(loc => proxyLocationDAO.saveProxy(loc))

              Future.sequence(proxyUpdateFutureList).map(proxyUpdateResults => {
                val failures = proxyUpdateResults.collect({ case Some(Left(err)) => err })
                if (failures.nonEmpty) {
                  logger.error(s"Could not copy all proxies:")
                  failures.foreach(err => logger.error(err.toString))

                  indexer.deleteById(state.destFileId.get)
                  deleteCopiedProxies(state.destFileProxy.get)
                  originalSender ! StepFailed(state, failures.map(_.toString).mkString(","))
                }

                logger.info("Updated entry and proxies")
                indexer.deleteById(state.sourceFileId)
                state.sourceFileProxies.get.map(loc => proxyLocationDAO.deleteProxyRecord(loc.proxyId))
                originalSender ! StepSucceeded(state)
              })
          })
        }).recover({
          case err: Throwable =>
            logger.error("Could not update index records:", err)
            indexer.deleteById(state.destFileId.get)
            deleteCopiedProxies(state.destFileProxy.get)
            originalSender ! StepFailed(state, err.toString)
        })
      }

    case RollbackStep(state)=>
      val originalSender = sender()

      val indexDeleteFuture= indexer.deleteById(state.destFileId.get)
      indexDeleteFuture.onComplete({
        case Success(Left(err))=>
          logger.warn(s"Could not rollback updated index record: $err")
        case Failure(err)=>
          logger.warn(s"Could not rollback updated index record: ", err)
        case Success(Right(_))=>
      })

      val proxyDeleteFuture=deleteCopiedProxies(state.destFileProxy.get)
      proxyDeleteFuture.onComplete({
        case Success(results)=>
          val failures = results.collect({case Left(err)=>err})
          if(failures.nonEmpty){
            failures.foreach(err=>logger.warn(s"Could not rollback specific proxy copy: $err"))
          }
        case Failure(err)=>
          logger.error("Rollback proxy delete future crashed: ", err)
      })

      Future.sequence(Seq(indexDeleteFuture, proxyDeleteFuture)).onComplete({
        case Success(_)=>originalSender ! StepSucceeded(state)
        case Failure(err)=>originalSender ! StepFailed(state, err.toString)
      })

  }
}
