package helpers

import akka.actor.ActorRef
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocationDAO, ProxyType}
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.{ProxyGenerators, RequestType}
import javax.inject.{Inject, Named}
import play.api.Logger

import scala.concurrent.{Await, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Success}

/**
  * akka sink that will send the provided [[ArchiveEntry]] objects to proxy
  */
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class CreateProxySink @Inject() (proxyGenerators: ProxyGenerators)(implicit proxyLocationDAO:ProxyLocationDAO) extends GraphStage[SinkShape[ArchiveEntry]]{
  private final val in:Inlet[ArchiveEntry] = Inlet.create("CreateProxySink.in")
  private val logger = Logger(getClass)

  override def shape: SinkShape[ArchiveEntry] = SinkShape.of(in)

  val dotFileRegex:Regex = ".*/\\.[^/]*".r
  val dotFileRoot:Regex = "^\\.[^/]*".r

  /**
    * return TRUE if the filepath is a dot-file, false otherwise
    * @param filePath
    */
  def isDotFile(filePath:String) = {
    filePath match {
      case dotFileRegex(_*)=>true
      case dotFileRoot(_*)=>true
      case _=>false
    }
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        val proxyType = proxyGenerators.defaultProxyType(elem)

        if(!isDotFile(elem.path)) {
          val operationFutures = Future.sequence(Seq(
            Some(proxyGenerators.requestProxyJob(RequestType.THUMBNAIL, elem, None)),
            Some(proxyGenerators.requestProxyJob(RequestType.ANALYSE, elem, None)),
            proxyType.map(pt => proxyGenerators.requestProxyJob(RequestType.PROXY, elem, Some(pt)))
          ).collect({ case Some(fut) => fut })).map(resultSeq => {
            val failures = resultSeq.collect({ case Failure(err) => err })
            if (failures.nonEmpty) {
              logger.error("Could not thumb and analyse: ")
              failures.foreach(err => logger.error("Error: ", err))
              Failure(failures.head)
            } else {
              Success(resultSeq.collect({ case Success(result) => result }))
            }
          })

          Await.result(operationFutures, 30 seconds) match {
            case Success(msg) =>
              logger.info(s"Bulk proxy trigger success: $msg")
            case Failure(err) =>
              logger.info(s"Bulk proxy trigger failed: $err")
          }
        } else {
          logger.info(s"Not proxying dot-file ${elem.path}")
        }
        pull(in)
      }
    })

    override def preStart(): Unit = {
      pull(in)
    }

  }
}
