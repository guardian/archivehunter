package helpers

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes
import akka.stream.Materializer
import akka.stream.alpakka.s3.acl.CannedAcl
import akka.stream.alpakka.s3.impl.S3Headers
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.slf4j.LoggerFactory
import play.api.Configuration

import java.net.URL
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * this class contains helper methods that allow for a user avatar to be written out to S3 and then passed
  * to the frontend via a presigned link
  * @param config server configuration object
  * @param s3ClientManager S3ClientManager for communicating with S3
  * @param system implicitly provided ActorSystem
  * @param mat implicitly provided Materializer
  */
@Singleton
class UserAvatarHelper @Inject() (config:Configuration, s3ClientManager: S3ClientManager)(implicit system:ActorSystem, mat:Materializer) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val alpakkaS3 = s3ClientManager.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))
  private val s3Client = s3ClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))
  private val sanitiser = "[^\\w\\d-_]".r

  protected def sanitisedKey(str: String):String = {
    sanitiser.replaceAllIn(str, "_")
  }

  /**
    * write the avatar data into S3 as an image for later retrieval.
    * if the `content` ByteBuffer has not been flipped (i.e. position>0) then it is flipped here.
    * @param username username associated with this avatar
    * @param content data content to write
    * @return a Future with the written ObjectMetadata, or a failed future if the configuration was not correct.
    *         catch this with .recover() or .onComplete()
    */
  def writeAvatarData(username: String, content:ByteBuffer) = {
    if(content.position()!=0) content.flip()

    val dataSource = Source
      .fromIterator(()=>content.array().toIterator)
      .map(ByteString.apply(_))

    config.getOptional[String]("externalData.avatarBucket") match {
      case Some(avatarBucket)=>
        alpakkaS3.putObject(
          avatarBucket,
          sanitisedKey(username),
          dataSource,
          content.remaining(),  //we should be at the start of the buffer
          ContentTypes.NoContentType, //FIXME: should determine content type of buffer somehow
          S3Headers(CannedAcl.Private)
        )
      case None=>
        logger.error("There is nothing configured for `externalData.avatarBucket` so user avatars will not work. Please update the configuration.")
        Future.failed(new RuntimeException("Invalid configuration, please see server logs"))
    }
  }

  /**
    * try to get the avatar for a given name from the bucket.
    * If successful, this will return a presigned URL suitable for client use directly to the S3 bucket.
    * If unsuccessful, a message is logged and None is returned
    * @param username the username to look up
    * @return an Option containing a java.net.URL suitable for sending back to the client.
    */
  def getAvatarUrl(username: String):Option[URL] = {
    config.getOptional[String]("externalData.avatarBucket").flatMap(avatarBucket=> {
      val expiry = Date.from(Instant.now().plusSeconds(900))  //link is valid for 15mins
      Try {
        s3Client.generatePresignedUrl(avatarBucket, sanitisedKey(username), expiry)
      } match {
        case Success(url)=>Some(url)
        case Failure(err)=>
          logger.error(s"Could not get avatar URL for user '$username': ${err.getMessage}", err)
          None
      }
    })
  }

  /**
    * get an S3 URL to the given user's avatar
    * @param username
    * @return
    */
  def getAvatarLocation(username:String) = {
    config.getOptional[String]("externalData.avatarBucket").map(avatarBucket=>{
      new URL("s3", avatarBucket, sanitisedKey(username))
    })
  }

  def getAvatarLocationString(username:String) = getAvatarLocation(username).map(_.toString)

  /**
    * get a presigned URL from the S3 URL. This is for client usage
    * @param s3Url the S3 URL to translate
    * @return either a client-facing https presigned URL or a Failure indicating the problem
    */
  def getPresignedUrl(s3Url:URL):Try[URL] = {
    config.getOptional[String]("externalData.avatarBucket") match {
      case Some(avatarBucket)=>
        if(s3Url.getProtocol!="s3") {
          Failure(new RuntimeException("getPresignedUrl requires an S3 URL"))
        } else if(s3Url.getHost!=avatarBucket){
          Failure(new RuntimeException("incorrect bucket name"))
        } else {
          val expiry = Date.from(Instant.now().plusSeconds(900))  //link is valid for 15mins
          Try {
            s3Client.generatePresignedUrl(avatarBucket, s3Url.getFile, expiry)
          }
        }
    }
  }
}
