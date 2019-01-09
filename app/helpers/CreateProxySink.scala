package helpers

import akka.actor.ActorRef
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocationDAO}
import com.theguardian.multimedia.archivehunter.common.cmn_services.ProxyGenerators
import javax.inject.{Inject, Named}
import play.api.Logger
import services.ETSProxyActor

import scala.concurrent.Await
import scala.util.{Failure, Success}

/**
  * akka sink that will send the provided [[ArchiveEntry]] objects to proxy
  */
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class CreateProxySink @Inject() (proxyGenerators: ProxyGenerators, @Named("etsProxyActor")etsProxyActor:ActorRef)(implicit proxyLocationDAO:ProxyLocationDAO) extends GraphStage[SinkShape[ArchiveEntry]]{
  private final val in:Inlet[ArchiveEntry] = Inlet.create("CreateProxySink.in")
  private val logger = Logger(getClass)

  override def shape: SinkShape[ArchiveEntry] = SinkShape.of(in)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        etsProxyActor ! ETSProxyActor.CreateDefaultMediaProxy(elem)

        Await.result(proxyGenerators.createThumbnailProxy(elem), 30 seconds) match {
          case Success(msg)=>
            logger.info(s"Bulk proxy trigger success: $msg")
          case Failure(err)=>
            logger.info(s"Bulk proxy trigger failed: $err")
        }
        pull(in)
      }
    })

    override def preStart(): Unit = {
      pull(in)
    }

  }
}
