package services

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.{Indexer, ProxyLocationDAO}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTarget
import javax.inject.Inject
import play.api.Configuration
import services.FileMove.GenericMoveActor.MoveActorMessage
import services.FileMove.{CopyMainFile, CopyProxyFiles, VerifySource}

//step one: verify file exists [VerifySource[

//step two: verify dest collection exists [Inline]

//step three: gather proxies [VerifySource]

//step four: copy file to new location

//step five: copy proxies to new location

//step six: if all copies succeed, update index records

//step seven: remove original files

object FileMoveActor {

  case class MoveFile(sourceFileId:String, destination:ScanTarget) extends MoveActorMessage

}
/**
  * this actor uses the same technique as Project Locker to run a step-function and roll back all successful stages if a
  * stage fails
  */
class FileMoveActor @Inject() (config:Configuration,
                               proxyLocationDAO: ProxyLocationDAO,
                               esClientManager:ESClientManager,
                               dynamoClientManager: DynamoClientManager,
                               s3ClientManager: S3ClientManager)(implicit system:ActorSystem)
  extends Actor {
  import FileMoveActor._
  import services.FileMove.GenericMoveActor._

  private implicit val mat:Materializer = ActorMaterializer.create(system)

  private implicit val esClient = esClientManager.getClient()
  private implicit val dynamoClient = dynamoClientManager.getNewAlpakkaDynamoClient()

  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")
  private val indexer = new Indexer(indexName)

  val fileMoveChain:Seq[ActorRef] = Seq(
    system.actorOf(Props(new VerifySource(indexer, proxyLocationDAO))),
    system.actorOf(Props(new CopyMainFile(s3ClientManager))),
    system.actorOf(Props(new CopyProxyFiles(s3ClientManager)))
  )

  override def receive:Receive = {
    case MoveFile(sourceFileId, destination)=>

  }
}
