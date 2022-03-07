package services.FileMove

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.http.scaladsl.model.{ContentType, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, StatusCode, StatusCodes}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.regions.{Region, Regions}
import helpers.S3Signer
import org.slf4j.LoggerFactory
import services.FileMove.ImprovedLargeFileCopier.HeadInfo

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
class ImprovedLargeFileCopier @Inject() (implicit actorSystem:ActorSystem, override val mat:Materializer, override val ec:ExecutionContext) extends S3Signer {
  override protected val logger = LoggerFactory.getLogger(getClass)
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

  private def doRequestSigning(reqparts:(HttpRequest, Unit), region:Regions, credentialsProvider:Option[AWSCredentialsProvider]) =
      credentialsProvider match {
        case Some(creds)=>
          signHttpRequest(reqparts._1, Region.getRegion(region), "s3", creds)
            .map(signedRequest=>(signedRequest, ()))
        case None=>Future(reqparts)
      }

  /**
    * Gets the metadata for a given object
    * @param region
    * @param credentialsProvider
    * @param sourceBucket
    * @param sourceKey
    * @param sourceVersion
    * @param poolClientFlow
    * @return a Future containing None if the object does not exist, an instance of HeadInfo if it does or a failure if
    *         another error occurred
    */
  def headSourceFile(region:Regions, credentialsProvider: Option[AWSCredentialsProvider], sourceBucket: String, sourceKey:String, sourceVersion:Option[Int])(implicit poolClientFlow:HostConnectionPool) = {
    val req = createRequest(HttpMethods.HEAD, region, sourceBucket, sourceKey, sourceVersion)(ImprovedLargeFileCopier.NoRequestCustomisation)
    Source
      .single(req)
      .mapAsync(1)(reqparts=>doRequestSigning(reqparts, region, credentialsProvider))
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

  private def loadResponseBody(response:HttpResponse) = response.entity
    .dataBytes
    .runWith(Sink.reduce[ByteString](_ ++ _))
    .map(_.utf8String)

  /**
    * Initiates a multipart upload to the given object.
    * @param region AWS region within which to operate
    * @param credentialsProvider AWS Credentials provider. This can be None, if so then no authentication takes place and the request is made anonymously
    * @param destBucket bucket to create the object in
    * @param destKey key to create the object with
    * @param metadata HeadInfo providing the content type metadata to use
    * @param poolClientFlow implicitly provided HostConnectionPool instance
    * @return a Future containing the upload ID. On error, the future will be failed
    */
  def initiateMultipartUpload(region:Regions, credentialsProvider:Option[AWSCredentialsProvider], destBucket:String, destKey:String, metadata:HeadInfo)
                             (implicit poolClientFlow:HostConnectionPool) = {
    val req = createRequest(HttpMethods.POST, region, destBucket, destKey, None) { partialRequest=>
      partialRequest
        .withUri(partialRequest.uri.withRawQueryString("?uploads"))
        .withHeaders(partialRequest.headers ++ Seq(
          RawHeader("Content-Type", metadata.contentType),
          RawHeader("x-amz-acl", "private"),
        ))
    }

    Source
      .single(req)
      .mapAsync(1)(reqparts=>doRequestSigning(reqparts, region, credentialsProvider))
      .via(poolClientFlow)
      .runWith(Sink.head)
      .flatMap({
        case (Success(response), _)=>
          (response.status: @switch) match {
            case StatusCodes.OK =>
              loadResponseBody(response)
                .map(scala.xml.XML.loadString)
                .map(elems => (elems \\ "UploadId").text)
                .map(uploadId => {
                  logger.info(s"Successfully initiated a multipart upload with ID $uploadId")
                  uploadId
                })
            case _=>
              loadResponseBody(response)
                .flatMap(errContent=> {
                  logger.error(s"Could not initiate multipart upload for s3://$destBucket/$destKey: ${response.status}")
                  logger.error(s"s3://$destBucket/$destKey: $errContent")
                  Future.failed(new RuntimeException(s"Server error ${response.status}"))
                })
          }
        case (Failure(err), _) =>
          logger.error(s"Could not initate multipart upload for s3://$destBucket/$destKey: ${err.getMessage}", err)
          Future.failed(err)
      })
  }

  def abortMultipartUpload(region:Regions, credentialsProvider:Option[AWSCredentialsProvider], sourceBucket:String, sourceKey:String, uploadId:String)
                          (implicit poolClientFlow:HostConnectionPool)= {
    Source
      .single(createRequest(HttpMethods.DELETE, region, sourceBucket, sourceKey, None) { partialRequest=>
        partialRequest.withUri(partialRequest.uri.withRawQueryString(s"?uploadId=$uploadId"))
      })
      .mapAsync(1)(reqparts=>doRequestSigning(reqparts, region, credentialsProvider))
      .via(poolClientFlow)
      .runWith(Sink.head)
      .flatMap({
        case (Success(response), _)=>
          (response.status: @switch) match {
            case StatusCodes.OK=>
              response.discardEntityBytes()
              logger.info(s"Successfully cancelled upload ID $uploadId")
              Future( () )
            case _=>
              loadResponseBody(response).map(errContent=>{
                logger.error(s"Could not cancel multipart upload $uploadId: server said ${response.status} $errContent")
                throw new RuntimeException(s"Server error ${response.status}")
              })
          }
      })
  }

  /**
    * Creates a new pool client flow. This should be considered internal and is only used externally in testing.
    * @param destRegion region to communicate with
    * @return
    */
  def newPoolClientFlow(destRegion:Regions) = {
    Http().cachedHostConnectionPoolHttps[Unit](s"s3.${destRegion.getName}.amazonaws.com")
  }

  def performUpload(sourceBucket:String, sourceKey:String, sourceVersion:Option[Int], destBucket:String, destKey:String, destVersion:Option[Int], destRegion:Regions) = {
    logger.info(s"Initiating ImprovedLargeFileCopier for region $destRegion")
    implicit val poolClientFlow = newPoolClientFlow(destRegion)

    headSourceFile(destRegion, None, sourceBucket, sourceKey, sourceVersion).flatMap({
      case Some(headInfo)=>
        for {
          uploadId <- initiateMultipartUpload(destBucket, destKey, destVersion)
        } yield uploadId
        Future.failed(new RuntimeException(s"Not implemented yet"))
      case None=>
        logger.error(s"Can't copy s3://$sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")} because the source file does not exist")
        Future.failed(new RuntimeException(s"Source file did not exist"))
    })


  }
}
