package com.theguardian.multimedia.archivehunter.common.clientManagers

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.S3Ext
import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client, S3ClientBuilder}

import javax.inject.{Inject, Singleton}

@Singleton
class S3ClientManager @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase[S3Client]{
  private var existingClientsMap:Map[Region,S3Client] = Map()

  override def getClient(profileName:Option[String]=None): S3Client = getS3Client(profileName)

  private def getCredentialsProvider(profileName:Option[String]) = {
    val b = DefaultCredentialsProvider.builder()
    profileName match {
      case Some(name)=> b.profileName(name).build()
      case None=>b.build()
    }
  }

  /**
    * Returns S3Settings containing our preferred credentials provider, suitable for use with Alpakka.
    * See https://doc.akka.io/docs/alpakka/current/s3.html#apply-s3-settings-to-a-part-of-the-stream for
    * how to use this
    * @param profileName optional AWS profile name to use (used in development)
    * @param actorSystem implicitly provided actorsystem reference
    * @return an S3Settings object
    */
  def getAlpakkaCredentials(profileName:Option[String]=None)(implicit actorSystem: ActorSystem) = {
    S3Ext(actorSystem).settings.withCredentialsProvider(getCredentialsProvider(profileName))
  }
  /**
    * Obtain an S3 client object for the given region, optionally using profile credentials.  This will re-use previously created
    * clients and not create a new one on every call.
    * @param profileName optional, name of AWS credentails profile to use. Used for locally based development.
    * @param region optional region to get the client for.  If not specified, then uses the default region from the confiuration.
    * @return the S3 client.
    */
  def getS3Client(profileName:Option[String]=None, region:Option[Region]=None):S3Client = {
    region match {
      case Some(rgn)=>
        existingClientsMap.get(rgn) match {
          case Some(client)=>client
          case None=>
            val newClient = S3Client
              .builder()
              .region(rgn)
              .credentialsProvider(getCredentialsProvider(profileName))
              .build()
            this.synchronized({
              existingClientsMap = existingClientsMap.updated(rgn, newClient)
            })
            newClient
        }
      case None=>
        S3Client.builder().credentialsProvider(getCredentialsProvider(profileName)).build()
    }
  }

  def flushCache = this.synchronized({
    existingClientsMap = Map()
  })

}
