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
        logger.debug(s"Looking up ${state.sourceFileId}")
        indexer.getById(state.sourceFileId).flatMap(entry => {
          logger.debug(s"Looked up entry: $entry")
          val updatedEntry = entry.copy(id = state.destFileId.get,bucket=state.destBucket)
          indexer.indexSingleItem(updatedEntry).flatMap({
            case Right(_)=>
              val proxyUpdateFutureList = state.destFileProxy.get.map(loc => proxyLocationDAO.saveProxy(loc))

              Future.sequence(proxyUpdateFutureList).map(proxyUpdateResults => {
                val failures = proxyUpdateResults.collect({ case Some(Left(err)) => err })
                if (failures.nonEmpty) {
                  logger.error(s"Could not copy all proxies:")
                  failures.foreach(err => logger.error(err.toString))

                  //don't do the rollback here, we will be requested to rollback by the supervisor and
                  //handle this in RollbackStep below
                  originalSender ! StepFailed(state, failures.map(_.toString).mkString(","))
                }

                logger.info("Updated entry and proxies")
                indexer.deleteById(state.sourceFileId)
                Future.sequence(state.sourceFileProxies.get.map(loc => proxyLocationDAO.deleteProxyRecord(loc.proxyId))).flatMap(results=>{
                  val failures = results.collect({case Left(err)=>err})
                  if(failures.nonEmpty){
                    logger.error(s"${failures.length} proxy copies failed: ")
                    failures.foreach(errMsg=>logger.error(s"\t$errMsg"))
                    Future.failed(new RuntimeException(s"${failures.length} proxy copies failed"))
                  } else {
                    originalSender ! StepSucceeded(state)
                    Future( () )
                  }
                })

              })
            case Left(dbErr)=>
              println(dbErr.toString)
              Future.failed(new RuntimeException(s"Could not update index: ${dbErr.toString}"))
          })
        }).recover({
          case err: Throwable =>
            println(err.getMessage)
            logger.error("Could not update index records:", err)
            //don't do the rollback here, we will be requested to rollback by the supervisor and
            //handle this in RollbackStep below
            originalSender ! StepFailed(state, err.toString)
        })
      }

    case RollbackStep(state)=>
      val originalSender = sender()

      if(state.entry.isEmpty || state.destFileId.isEmpty){
        sender() ! StepFailed(state, "Not enough state elements were defined")
      } else {
        val resaveFuture = indexer.getById(state.destFileId.get).flatMap(destEntry => {
          val reconstitutedSource = destEntry.copy(id = state.sourceFileId, bucket = state.entry.get.bucket)
          indexer.indexSingleItem(reconstitutedSource)
        })

        val indexDeleteFuture = resaveFuture.map({
          case Right(_) =>
            indexer.deleteById(state.destFileId.get)
            Right(())
          case problem@Left(err) =>
            logger.error(s"Could not re-create deleted source entry: $err")
            problem
        })

        indexDeleteFuture.onComplete({
          case Success(Left(err)) =>
            logger.warn(s"Could not rollback updated index record: $err")
          case Failure(err) =>
            logger.warn(s"Could not rollback updated index record: ", err)
          case Success(Right(_)) =>
        })

        val resaveProxiesFuture = state.sourceFileProxies
          .map(proxySeq =>
            Future.sequence(proxySeq.map(proxyLocationDAO.saveProxy))
              .map(_.collect({ case Some(result) => result }))
          )
          .getOrElse(Future(Seq()))
          .map(resultSeq => {
            val failures = resultSeq.collect({ case Left(err) => err })
            if (failures.nonEmpty) {
              logger.error(s"${failures.length} proxies failed to restore: ")
              failures.foreach(err => logger.error(s"\t${err.toString}"))
              //fail the future if we can't resave. This will prevent the proxy entries being deleted below.
              throw new RuntimeException(s"${failures.length} proxies failed to restore")
            } else {
              resultSeq
            }
          })

        val proxyDeleteFuture = resaveProxiesFuture.flatMap(resultSeq =>
          deleteCopiedProxies(state.destFileProxy.get).map(_ ++ resultSeq)
        )

        proxyDeleteFuture.onComplete({
          case Success(results) =>
            val failures = results.collect({ case Left(err) => err })
            if (failures.nonEmpty) {
              failures.foreach(err => logger.warn(s"Could not rollback specific proxy copy: $err"))
            }
          case Failure(err) =>
            logger.error("Rollback proxy delete future crashed: ", err)
        })

        Future.sequence(Seq(indexDeleteFuture, proxyDeleteFuture)).onComplete({
          case Success(_) => originalSender ! StepSucceeded(state)
          case Failure(err) =>
            logger.error("Could not roll back index records: ", err)
            originalSender ! StepFailed(state, err.toString)
        })
      }
  }
}
