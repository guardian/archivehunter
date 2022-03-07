package services.FileMove

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.{ContentType, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, StatusCode, StatusCodes}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.amazonaws.regions.{Region, Regions}
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import javax.inject.{Inject, Singleton}
import scala.annotation.switch
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ImprovedLargeFileCopier {
  def NoRequestCustomisation(httpRequest: HttpRequest) = httpRequest

  case class HeadInfo(
                     bucket:String,
                     key:String,
                     version:Option[Int],
                     lastModified:String,
                     contentLength:Long,
                     eTag:Option[String],
                     contentType:String,
                     contentEncoding:Option[String],
                     contentLanguage:Option[String],
                     )

  object HeadInfo {
    /**
      * Build a HeadInfo object from provided HTTP headers. The resulting HeadInfo is NOT validated.
      * @param bucket
      * @param key
      * @param version
      * @param headers
      * @return
      */
    def apply(bucket:String, key:String, version:Option[Int], headers:Seq[HttpHeader], entityContentType:ContentType, entityContentLength:Option[Long]) = {
      def updateHeadInfo(existing:HeadInfo, nextHeader:HttpHeader) = (nextHeader.name(): @switch) match {
        case "Last-Modified"=>existing.copy(lastModified=nextHeader.value())
        case "Content-Length"=>existing.copy(contentLength=Try { nextHeader.value().toLong}.toOption.getOrElse(-1L))
        case "ETag"=>existing.copy(eTag=Some(nextHeader.value()))
        case "Content-Type"=>existing.copy(contentType=nextHeader.value())
        case "Content-Encoding"=>existing.copy(contentEncoding=Some(nextHeader.value()))
        case "Content-Language"=>existing.copy(contentLanguage=Some(nextHeader.value()))
        case _=>existing
      }
      val initial = new HeadInfo(bucket, key, version, "", -1L, None, "", None,None)
      val fromHeaders = headers.foldLeft(initial)((acc,elem)=>updateHeadInfo(acc, elem))

      val withContentType = if(fromHeaders.contentType=="") {
        fromHeaders.copy(contentType = entityContentType.toString())
      } else {
        fromHeaders
      }

      if(withContentType.contentLength == -1L && entityContentLength.isDefined) {
        withContentType.copy(contentLength = entityContentLength.get)
      } else {
        withContentType
      }
    }
  }
}

/**
  * Uses the "multipart-copy" AWS API directly via an Akka connection pool
  */
@Singleton
class ImprovedLargeFileCopier @Inject() (implicit actorSystem:ActorSystem, mat:Materializer, ec:ExecutionContext) {
  private final val logger = LoggerFactory.getLogger(getClass)
  //Full type of "poolClientFlow" to save on typing :)
  type HostConnectionPool =  Flow[(HttpRequest, Unit), (Try[HttpResponse], Unit), Http.HostConnectionPool]

  /**
    * Creates a generic Akka HttpRequest object with the given parameters.  If further customisation is required, provide
    * a callback which will take the built HttpRequest and change it, returning the updated copy. If not, then pass
    * ImprovedLargeFileCopier.NoRequestCustomisation as the callback.
    * This is used in a graph to provide an input to the poolClientFlow.
    * @param method HTTP method
    * @param region AWS region to talk to S3
    * @param sourceBucket source bucket name
    * @param sourceKey source key
    * @param sourceVersion optional, version of the source file. Specify None if not using versioning or to default to latest
    * @param customiser callback function that can be used to update the request model and return it
    * @return a tuple of HttpRequest and Unit
    */
  protected def createRequest(method:HttpMethod, region:Regions, sourceBucket:String, sourceKey:String, sourceVersion:Option[Int])
                   (customiser:((HttpRequest)=>HttpRequest)) = (
    {
      val bucketAndKey = Paths.get(sourceBucket, sourceKey).toString
      val params = sourceVersion match {
        case Some(version)=> bucketAndKey + s"?versionId=$version"
        case None=> bucketAndKey
      }
      val targetUri = s"https://s3.${region.getName}.amazonaws.com/$params"
      logger.info(s"Target URI is $targetUri")
      customiser(HttpRequest(method, uri=targetUri, headers=Seq()))//Host(s"$sourceBucket.s3.amazonaws.com"))))
    },
    ()
  )

  def uploadParts(region:Regions, sourceBucket:String, sourceKey:String, sourceVersion:Option[Int]) = {

  }

  def headSourceFile(region:Regions, sourceBucket: String, sourceKey:String, sourceVersion:Option[Int])(implicit poolClientFlow:HostConnectionPool) = {
    val req = createRequest(HttpMethods.HEAD, region, sourceBucket, sourceKey, sourceVersion)(ImprovedLargeFileCopier.NoRequestCustomisation)
    Source
      .single(req)
      .via(poolClientFlow)
      .runWith(Sink.head)
      .map({
        case (Success(result), _) =>
          (result.status: @switch) match {
            case StatusCodes.OK =>
              logger.info(s"HEAD success on $sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")}")
              result.headers.foreach(hdr => {
                logger.debug(s"\t${hdr.name()} => ${hdr.value()}")
              })
              result.discardEntityBytes()
              Some(ImprovedLargeFileCopier.HeadInfo(sourceBucket, sourceKey, sourceVersion,
                result.headers, result.entity.contentType, result.entity.contentLengthOption)
              )
            case StatusCodes.NotFound=>
              result.discardEntityBytes()
              logger.warn(s"No file found for $sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")}")
              None
            case StatusCodes.Forbidden=>
              val contentFut = result
                .entity
                .dataBytes
                .runWith(Sink.fold(ByteString.empty)(_ ++ _))
              //only for debugging!!
              val content = Await.result(contentFut, 10.seconds)

              logger.error(s"Access was forbidden to $sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")}: ${content.utf8String}")
              throw new RuntimeException("Access forbidden")
            case _=>
              result.discardEntityBytes()
              logger.error(s"Unexpected response from S3 in HEAD operation: ${result.status.value}")
              throw new RuntimeException(s"Unexpected response from S3: ${result.status.value}")
          }
        case (Failure(err), _) =>
          logger.error(s"Could not retrieve metadata for s3://$sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")}:" +
            s" ${err.getMessage}", err)
          throw err //fail the future
      })
  }

  def newPoolClientFlow(destRegion:Regions) = {
    Http().cachedHostConnectionPoolHttps[Unit](s"s3.${destRegion.getName}.amazonaws.com")
  }

  def performUpload(sourceBucket:String, sourceKey:String, sourceVersion:Option[Int], destBucket:String, destKey:String, destVersion:Option[Int], destRegion:Regions) = {
    logger.info(s"Initiating ImprovedLargeFileCopier for region $destRegion")
    implicit val poolClientFlow = newPoolClientFlow(destRegion)

    headSourceFile(destRegion, sourceBucket, sourceKey, sourceVersion).flatMap({
      case Some(headInfo)=>
//        for {
//          uploadId <- initiateMultipartUpload(destBucket, destKey, destVersion)
//        } yield uploadId
        Future.failed(new RuntimeException(s"Not implemented yet"))
      case None=>
        logger.error(s"Can't copy s3://$sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")} because the source file does not exist")
        Future.failed(new RuntimeException(s"Source file did not exist"))
    })


  }
}
