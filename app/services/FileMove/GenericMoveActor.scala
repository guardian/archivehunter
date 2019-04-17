package services.FileMove

import akka.actor.Actor
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation}
import play.api.Logger

object GenericMoveActor {
  trait MoveActorMessage

  case class FileMoveTransientData(sourceFileId:String, entry:Option[ArchiveEntry], destFileId:Option[String], sourceFileProxies:Option[Seq[ProxyLocation]],
                                   destFileProxy:Option[Seq[ProxyLocation]], destBucket:String, destProxyBucket:String)

  case class PerformStep(state:FileMoveTransientData) extends MoveActorMessage
  case class RollbackStep(state:FileMoveTransientData) extends MoveActorMessage

  case class StepSucceeded(updatedData:FileMoveTransientData) extends MoveActorMessage
  case class StepFailed(updatedData:FileMoveTransientData, err:String) extends MoveActorMessage
}

trait GenericMoveActor extends Actor {
  protected val logger = Logger(getClass)


}