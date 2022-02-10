package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import javax.inject.{Inject, Singleton}

@Singleton
class S3ClientManager @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase[AmazonS3]{
  private var existingClientsMap:Map[String,AmazonS3] = Map()

  override def getClient(profileName:Option[String]=None): AmazonS3 = getS3Client(profileName)

  /**
    * Obtain an S3 client object for the given region, optionally using profile credentials.  This will re-use previously created
    * clients and not create a new one on every call.
    * @param profileName optional, name of AWS credentails profile to use. Used for locally based development.
    * @param region optional region to get the client for.  If not specified, then uses the default region from the confiuration.
    * @return the S3 client.
    */
  def getS3Client(profileName:Option[String]=None, region:Option[String]=None):AmazonS3 = {
    region match {
      case Some(rgn)=>
        existingClientsMap.get(rgn) match {
          case Some(client)=>client
          case None=>
            val newClient = AmazonS3ClientBuilder.standard().withRegion(rgn).withCredentials(credentialsProvider(profileName)).build()
            this.synchronized({
              existingClientsMap = existingClientsMap.updated(rgn, newClient)
            })
            newClient
        }
      case None=>
        AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()
    }
  }

  def flushCache = this.synchronized({
    existingClientsMap = Map()
  })

}
