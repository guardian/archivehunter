package StreamComponents

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.google.inject.Injector
import com.gu.scanamo.error.DynamoReadError
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation, ProxyLocationDAO, ProxyType}
import models.ProxyVerifyResult

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * communicates (synchronously) with DynamoDB to verify if there is a proxy of the given type for each item
  * @param proxyType ProxyType value to check for
  * @param injector injector instance so we can initiate components
  */
class VerifyProxy (proxyType: ProxyType.Value, injector:Injector) extends GraphStage[FlowShape[ProxyVerifyResult,ProxyVerifyResult]]{
  final val in:Inlet[ProxyVerifyResult] = Inlet.create("VerifyProxy.in")
  final val out:Outlet[ProxyVerifyResult] = Outlet.create("VerifyProxy.out")

  override def shape: FlowShape[ProxyVerifyResult, ProxyVerifyResult] = FlowShape.of(in, out)

  val maxAttempts = 50

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val proxyLocationDAO = injector.getInstance(classOf[ProxyLocationDAO])
    val dynamoClientManager = injector.getInstance(classOf[DynamoClientManager])

    implicit val dynamoClient = dynamoClientManager.getNewDynamoClient()

    /**
      * call synchronous getProxy and retry up to maxAttempts times, backing off by an extra 1/2 second each time
      * @param fileId file ID to look up
      * @param proxyType proxy type for file
      * @return None if no entry exists. Some(Left(error)) if an error occurred or Some(Right(Result)) if an entry does exist.
      */
    def getProxySyncWithBackoff(fileId:String, proxyType:ProxyType.Value, attempt:Int=0):Option[Either[DynamoReadError, ProxyLocation]] =
      proxyLocationDAO.getProxySync(fileId, proxyType) match {
        case Some(Left(err))=>
          if(attempt+1>maxAttempts){
            Some(Left(err))
          } else {
            Thread.sleep(500*attempt)
            getProxySyncWithBackoff(fileId, proxyType, attempt+1)
          }
        case Some(Right(result))=>Some(Right(result))
        case None=>None
      }

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        println(s"DEBUG: checking proxy for $elem")
        val result = getProxySyncWithBackoff(elem.fileId, proxyType) match {
          case Some(Left(err))=>
            println(s"Could not read Dynamodb after $maxAttempts attempts: ${err.toString}")
            throw new RuntimeException("DynamoDB error")
          case Some(Right(proxyEntry))=>
            println(s"DEBUG: Got $proxyType proxy $proxyEntry for $elem")
            elem.copy(haveProxy = Some(true))
          case None=>
            println(s"DEBUG: Got no $proxyType proxy for $elem")
            elem.copy(haveProxy = Some(false))
        }

        push(out, result)
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        //println(s"verifyProxy $proxyType: pull from downstream")
        pull(in)
      }
    })
  }
}
