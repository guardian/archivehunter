package services

import akka.actor.Actor
import com.theguardian.multimedia.archivehunter.common.clientManagers.SESClientManager
import javax.inject.Inject
import play.api.Configuration

object SendEmailActor {
  trait SEMsg

  //public messages to send to the actor

}


//class SendEmailActor @Inject() (config:Configuration, SESClientManager: SESClientManager) extends Actor {
//
//  override def receive: Receive = {
//
//  }
//}
