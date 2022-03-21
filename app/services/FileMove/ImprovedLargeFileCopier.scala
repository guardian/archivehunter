package services.FileMove

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, StatusCode, StatusCodes}
import akka.stream.{KillSwitches, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.amazonaws.regions.{Region, Regions}
import helpers.S3Signer
import org.slf4j.LoggerFactory
import services.FileMove.ImprovedLargeFileCopier.{CompletedUpload, HeadInfo, UploadPart, UploadedPart, copySourcePath}
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import javax.inject.{Inject, Singleton}
import scala.annotation.switch
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ImprovedLargeFileCopier {
  private val logger = LoggerFactory.getLogger(getClass)
  def NoRequestCustomisation(httpRequest: HttpRequest) = httpRequest

  val defaultPartSize:Long = 10*1024*1024  //default chunk size is 10Mb

  /**
    * AWS specifications say that parts must be at least 5Mb in size but no more than 5Gb in size, and that there
    * must be a maximum of 10,000 parts for an upload.  This makes the effective file size limit 5Tb
    * @param totalFileSize actual size of the file to upload, in bytes
    * @return the target path size
    */
  def estimatePartSize(totalFileSize:Long):Long = {
    val maxWantedParts = 10000

    var partSize:Long = defaultPartSize
    var nParts:Int = maxWantedParts + 1
    var i:Int=1
    while(true) {
      nParts = Math.ceil(totalFileSize.toDouble / partSize.toDouble).toInt
      if (nParts > maxWantedParts) {
        i = i + 1
        partSize = defaultPartSize * i
      } else {
        logger.info(s"Part size estimated at $partSize for $nParts target parts")
        return partSize
      }
    }
    defaultPartSize
  }

  /**
    * returns a path suitable for the `x-amz-copy-source` header. This is URL-encoded.
    */
  def copySourcePath(bucket:String, key:String, version:Option[String]) = {
    URLEncoder.encode(s"$bucket/$key${version.map(v=>s"?versionId=$v").getOrElse("")}", StandardCharsets.UTF_8)
  }

  case class HeadInfo(
                     bucket:String,
                     key:String,
                     version:Option[String],
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
    def apply(bucket:String, key:String, version:Option[String], headers:Seq[HttpHeader], entityContentType:ContentType, entityContentLength:Option[Long]) = {
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

  case class CompletedUpload(location:String, bucket:String, key:String, eTag:String, crc32:Option[String], crc32c:Option[String], sha1:Option[String], sha256:Option[String])
  object CompletedUpload {
    def fromXMLString(xmlString:String) = {
      for {
        parsed <- Try { scala.xml.XML.loadString(xmlString) }
        result <- Try {
          new CompletedUpload(
            (parsed \\ "Location").text,
            (parsed \\ "Bucket").text,
            (parsed \\ "Key").text,
            (parsed \\ "ETag").text,
            (parsed \\ "ChecksumCRC32").headOption.map(_.text),
            (parsed \\ "ChecksumCRC32C").headOption.map(_.text),
            (parsed \\ "ChecksumSHA1").headOption.map(_.text),
            (parsed \\ "ChecksumSHA256").headOption.map(_.text),
          )
        }
      } yield result
    }
  }

  case class UploadPart(bucket:String, key:String, start:Long, end:Long, partNumber:Int)
  case class UploadedPart(partNumber:Int, uploadId:String, uploadedETag:String) {
    /**
      * returns an XML element for inclusion in the CompleteMultipartUpload request
      * @return
      */
    def toXml = <Part>
      <ETag>{uploadedETag}</ETag>
      <PartNumber>{partNumber}</PartNumber>
      </Part>
  }

  private val etagPartsXtractor = "\"*.*-(\\d+)\"*$".r

  def partsFromEtag(etag:String) = etag match {
    case etagPartsXtractor(partCount)=>
      val partCountInt = partCount.toInt  //this should be safe because the regex ensures that partCount is only digits
      logger.debug(s"etag of $etag gives partCount of $partCountInt")
      Some(partCountInt)
    case _=>
      logger.error(s"Could not get parts count from etag $etag")
      None
  }
  /**
    * builds a list of UploadPart instances corresponding to the given file
    * @param metadata HeadInfo describing the _source_ file
    * @return a sequence of UploadPart instances, representing the start and end points of each chunk of the upload
    */
  def deriveParts(destBucket:String, destKey:String, metadata:HeadInfo):Seq[UploadPart] = {
    val partSize = estimatePartSize(metadata.contentLength)
    var ptr: Long = 0
    var ctr: Int = 1 //partNumber starts from 1 according to the AWS spec
    var output = scala.collection.mutable.ListBuffer[UploadPart]()
    while (ptr < metadata.contentLength) {
      val chunkEnd = if (ptr + partSize > metadata.contentLength) {
        metadata.contentLength - 1 //range is zero-based so the last byte is contentLength-1
      } else {
        ptr + partSize - 1
      }
      output = output :+ UploadPart(destBucket, destKey, ptr, chunkEnd, ctr)
      ctr += 1
      ptr += partSize
    }
    logger.info(s"s3://${destBucket}/${destKey} - ${output.length} parts of ${partSize} bytes each")
    output.toSeq
  }

}

/**
  * Uses the "multipart-copy" AWS API directly via an Akka connection pool
  */
@Singleton
class ImprovedLargeFileCopier @Inject() (implicit actorSystem:ActorSystem, override val mat:Materializer, override val ec:ExecutionContext) extends S3Signer {
  override protected val logger = LoggerFactory.getLogger(getClass)
  //Full type of "poolClientFlow" to save on typing :)
  type HostConnectionPool[T] =  Flow[(HttpRequest, T), (Try[HttpResponse], T), Http.HostConnectionPool]

  def makeS3Uri(region:Regions, bucket:String, key:String, maybeVersion:Option[String]) = {
    val bucketAndKey = Paths
      .get("/", bucket, key)
      .toString
      .split("/")
      .map(URLEncoder.encode(_, StandardCharsets.UTF_8))
      .mkString("/")

    logger.debug(s"makeS3Uri - URL path is $bucketAndKey")
    new URI("https", s"s3.${region.getName}.amazonaws.com", bucketAndKey, maybeVersion.map(v => s"versionId=$v").orNull, null).toString
  }

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
  protected def createRequestFull[T](method:HttpMethod, region:Regions, sourceBucket:String, sourceKey:String, sourceVersion:Option[String], extraData:T)
                   (customiser:((HttpRequest)=>HttpRequest)) = (
    {
      val targetUri = makeS3Uri(region, sourceBucket, sourceKey, sourceVersion)
      logger.debug(s"Target URI is $targetUri")
      customiser(HttpRequest(method, uri=targetUri, headers=Seq()))
    },
    extraData
  )

  protected def createRequest(method:HttpMethod, region:Regions, sourceBucket:String, sourceKey:String, sourceVersion:Option[String])
                             (customiser:((HttpRequest)=>HttpRequest)) =
    createRequestFull[Unit](method, region, sourceBucket, sourceKey, sourceVersion, ())(customiser)

  /**
    * Builds a stream and performs a chunked copy
    * @param region AWS region to work in
    * @param credentialsProvider AWS credentials provider. If not given then no authentication is used
    * @param uploadId multipart upload ID. This must be created with `InitiateMultipartUpload`
    * @param parts a list of `UploadPart` instances describing the chunks to copy the file with
    * @param metadata HeadInfo object describing the _source_ file
    * @param poolClientFlow implicitly provided HostConnectionPool
    * @return a Future that completes when all parts are confirmed. If any parts fail then errors are logged and the future fails.
    *         On success, the Future contains a sequence of `UploadPart` objects which have the etag of the completed part.
    *         NOTE: these are not necessarily in order!
    */
  def sendPartCopies(region:Regions, credentialsProvider:Option[AwsCredentialsProvider], uploadId:String, parts:Seq[UploadPart], metadata:HeadInfo)
                 (implicit poolClientFlow:HostConnectionPool[UploadPart]) = {
    val partCount = parts.length

    val coreCount = Runtime.getRuntime.availableProcessors()
    val parallelism = if(coreCount>=4) (coreCount/2) - 1 else 1
    logger.debug(s"sendPartCopies - paralellism is $parallelism based on $coreCount available processors")

    Source.fromIterator(()=>parts.iterator)
      .map(uploadPart=>{
        logger.info(s"Write s3://${uploadPart.bucket}/${uploadPart.key} part ${uploadPart.partNumber} - building request for ${uploadPart.start}->${uploadPart.end}")
        createRequestFull(HttpMethods.PUT, region, uploadPart.bucket, uploadPart.key, None, uploadPart) { partialReq=>
          partialReq
          .withUri(partialReq.uri.withRawQueryString(s"partNumber=${uploadPart.partNumber}&uploadId=$uploadId"))
          .withHeaders(partialReq.headers ++ Seq(
            RawHeader("x-amz-copy-source", copySourcePath(metadata.bucket, metadata.key, metadata.version)),
            RawHeader("x-amz-copy-source-range", s"bytes=${uploadPart.start}-${uploadPart.end}"),
          ))
        }
      })
      .mapAsync(parallelism)(reqparts=>{
        val uploadPart = reqparts._2
        logger.info(s"Write s3://${uploadPart.bucket}/${uploadPart.key} part ${uploadPart.partNumber} - signing request for ${uploadPart.start}->${uploadPart.end}")
        doRequestSigning(reqparts._1, region, credentialsProvider).map(req=>(req, reqparts._2))
      })
      .via(poolClientFlow)
      .mapAsyncUnordered(parallelism)({
        case (Success(response), uploadPart)=>
          logger.info(s"Write s3://${uploadPart.bucket}/${uploadPart.key} part ${uploadPart.partNumber} - received response ${response.status}")
          loadResponseBody(response).map(responseBody=>{
            (response.status: @switch) match {
              case StatusCodes.OK=>
                val maybeEtag = for {
                  xmlContent <- Try { scala.xml.XML.loadString(responseBody) }
                  etag <- Try { (xmlContent \\ "ETag").text }
                } yield etag

                maybeEtag match {
                  case Success(contentEtag)=>
                    logger.info(s"s3://${metadata.bucket}/${metadata.key}: Uploaded part ${uploadPart.partNumber}/$partCount successfully, etag was $contentEtag.")
                    Some(UploadedPart(uploadPart.partNumber, uploadId, contentEtag))
                  case Failure(err)=>
                    logger.error(s"s3://${metadata.bucket}/${metadata.key}: could not understand XML content. Error was ${err.getMessage}")
                    logger.error(s"s3://${metadata.bucket}/${metadata.key}: content was $responseBody")
                    None
                }
              case _=>
                logger.error(s"s3://${metadata.bucket}/${metadata.key}: server returned error ${response.status}")
                logger.error(s"s3://${metadata.bucket}/${metadata.key}: $responseBody")
                None
            }
          })
        case (Failure(err), _)=>
          logger.error(s"s3://${metadata.bucket}/${metadata.key}: could not complete upload part request: ${err.getMessage}", err)
          Future(None)
      })
      .toMat(Sink.seq)(Keep.right)
      .run()
      .map(results=>{
        logger.info(s"s3://${metadata.bucket}/${metadata.key} copied all parts.  Received ${results.length} results")
        val failures = results.count(_.isEmpty)
        logger.info(s"s3://${metadata.bucket}/${metadata.key} $failures /${results.length} errors")
        if(failures>0) {
          logger.error(s"s3://${metadata.bucket}/${metadata.key}: $failures parts out of ${results.length} failed")
          throw new RuntimeException(s"s3://${metadata.bucket}/${metadata.key}: $failures parts out of ${results.length} failed")
        } else {
          logger.info(s"s3://${metadata.bucket}/${metadata.key}: all results passed")
          results.collect({case Some(part)=>part})
        }
      })
  }

  private def doRequestSigning(req:HttpRequest, region:Regions, credentialsProvider:Option[AwsCredentialsProvider]) =
      credentialsProvider match {
        case Some(creds)=>
          logger.info(s"Signing request from ${creds.resolveCredentials().accessKeyId()}")
          val reqWithHost = req.withHeaders(req.headers :+ Host(req.uri.authority))
          signHttpRequest(reqWithHost, Region.getRegion(region), "s3", creds)
        case None=>
          logger.warn(s"No credentials provider, attempting un-authenticated access")
          Future(req)
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
  def headSourceFile(region:Regions, credentialsProvider: Option[AwsCredentialsProvider], sourceBucket: String, sourceKey:String, sourceVersion:Option[String])
                    (implicit poolClientFlow:HostConnectionPool[Any]) = {
    val req = createRequest(HttpMethods.HEAD, region, sourceBucket, sourceKey, sourceVersion)(ImprovedLargeFileCopier.NoRequestCustomisation)
    Source
      .single(req)
      .mapAsync(1)(reqparts=>doRequestSigning(reqparts._1, region, credentialsProvider).map(req=>(req, reqparts._2)))
      .map(reqparts=>{
        logger.info(s"Request to send has ${reqparts._1.headers.length} headers.  Signed request headers:")
        reqparts._1.headers.foreach(hdr=>logger.info(s"\t${hdr.name()}: ${hdr.value()}"))
        reqparts
      })
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

              logger.error(s"Access was forbidden to $sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")}: '${content.utf8String}'")
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
  def initiateMultipartUpload(region:Regions, credentialsProvider:Option[AwsCredentialsProvider], destBucket:String, destKey:String, metadata:HeadInfo)
                             (implicit poolClientFlow:HostConnectionPool[Any]) = {
    val req = createRequest(HttpMethods.POST, region, destBucket, destKey, None) { partialRequest=>
      val contentType = ContentType.parse(metadata.contentType) match {
        case Right(ct)=>ct
        case Left(errs)=>
          logger.warn(s"S3 provided content-type '${metadata.contentType}' was not acceptable to Akka: ${errs.mkString(";")}'")
          ContentTypes.`application/octet-stream`
      }

      partialRequest
        .withUri(partialRequest.uri.withRawQueryString("uploads"))
        .withHeaders(partialRequest.headers ++ Seq(
          RawHeader("x-amz-acl", "private"),
        ))
        .withEntity(HttpEntity.empty(contentType))
    }

    Source
      .single(req)
      .mapAsync(1)(reqparts=>doRequestSigning(reqparts._1, region, credentialsProvider).map(rq=>(rq, ())))
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

  /**
    * completes an in-progress multipart upload.  This tells S3 to combine all the parts into one file and verify it.
    * @param region AWS region within which to operate
    * @param credentialsProvider AWS credentials provider for authentication. If None then no authentication is performed.
    * @param destBucket bucket that is being written to
    * @param destKey file that is being written to
    * @param uploadId upload ID of the in-progress upload
    * @param parts a list of `UploadedPart` giving details of the individual part copies
    * @param poolClientFlow implicitly provided HostConnectionPool instance
    * @return a Future containing a CompletedUpload instance. On error, the future will be failed.
    */
  def completeMultipartUpload(region:Regions, credentialsProvider:Option[AwsCredentialsProvider], destBucket:String, destKey:String, uploadId:String, parts:Seq[UploadedPart])
                             (implicit poolClientFlow:HostConnectionPool[Any]) = {
    val xmlContent = <CompleteMultipartUpload xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
      {
      parts.sortBy(_.partNumber).map(_.toXml) //must put the parts into ascending order
      }
    </CompleteMultipartUpload>

    val req = createRequest(HttpMethods.POST, region, destBucket, destKey, None) { partialReq=>
      partialReq
        .withUri(partialReq.uri.withRawQueryString(s"uploadId=$uploadId"))
        .withEntity(HttpEntity(xmlContent.toString()))
    }

    Source
      .single(req)
      .mapAsync(1)(reqparts=>doRequestSigning(reqparts._1, region, credentialsProvider).map(rq=>(rq, ())))
      .via(poolClientFlow)
      .runWith(Sink.head)
      .flatMap({
        case (Success(response), _)=>
          loadResponseBody(response).map(content=>{
            (response.status: @switch) match {
              case StatusCodes.OK=>
                CompletedUpload.fromXMLString(content) match {
                  case Success(completedUpload)=>
                    logger.info(s"Copy to s3://$destBucket/$destKey completed with eTag ${completedUpload.eTag}")
                    completedUpload
                  case Failure(err)=>
                    logger.error(s"Copy to s3://$destBucket/$destKey completed but could not parse the response: ${err.getMessage}")
                    logger.error(s"s3://$destBucket/$destKey raw response was $content")
                    throw new RuntimeException("Could not parse completed-upload response")
                }
              case _=>
                logger.error(s"Copy to s3://$destBucket/$destKey failed with error ${response.status}: $content")
                throw new RuntimeException(s"Server error ${response.status}")
            }
          })
        case (Failure(err), _)=>
          logger.error(s"Could not complete copy to s3://$destBucket/$destKey: ${err.getMessage}", err)
          Future.failed(err)
      })
  }

  /**
    * Cancels an in-progress multpart upload. This should always be called when aborting to minimise charges
    * @param region AWS region to operate in
    * @param credentialsProvider AWS credentials provider. If None the request is attempted without authentication
    * @param sourceBucket bucket containing the file being upload
    * @param sourceKey path to the file being uploaded
    * @param uploadId multipart upload ID
    * @param poolClientFlow implicitly provided HostConnectionPool
    * @return a Future with no value. On error, the Future will fail.
    */
  def abortMultipartUpload(region:Regions, credentialsProvider:Option[AwsCredentialsProvider], sourceBucket:String, sourceKey:String, uploadId:String)
                          (implicit poolClientFlow:HostConnectionPool[Any])= {
    logger.info(s"Aborting multipart upload s3://$sourceBucket/$sourceKey with ID $uploadId")
    Source
      .single(createRequest(HttpMethods.DELETE, region, sourceBucket, sourceKey, None) { partialRequest=>
        partialRequest.withUri(
          partialRequest.uri
            .withRawQueryString(s"uploadId=${URLEncoder.encode(uploadId, StandardCharsets.UTF_8)}")
        )
      })
      .mapAsync(1)(reqparts=>doRequestSigning(reqparts._1, region, credentialsProvider).map(rq=>(rq, ())))
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
  def newPoolClientFlow[T](destRegion:Regions) = {
    Http().cachedHostConnectionPoolHttps[T](s"s3.${destRegion.getName}.amazonaws.com")
  }

  /**
    * Performs a multipart copy operation
    * @param destRegion Region to operate in. This method does not currently support cross-region copying
    * @param credentialsProvider AwsCredentialsProvider used for signing requests. If this is None then the requests are attempted unauthenticated
    * @param sourceBucket bucket to copy from
    * @param sourceKey file to copy from
    * @param sourceVersion optional version of the file to copy from
    * @param destBucket bucket to copy to
    * @param destKey file to copy to.  You can't specify a specific version, if versioning is enabled on destBucket then a version
    *                is created and if not an existing file is overwritten
    * @return a Future, containing a CompletedUpload object.  On error, the partial upload is aborted and a failed future is returned.
    */
  def performCopy(destRegion:Regions, credentialsProvider: Option[AwsCredentialsProvider], sourceBucket:String, sourceKey:String,
                    sourceVersion:Option[String], destBucket:String, destKey:String) = {
    logger.info(s"Initiating ImprovedLargeFileCopier for region $destRegion")
    implicit val poolClientFlow = newPoolClientFlow[UploadPart](destRegion)
    implicit val genericPoolFlow = poolClientFlow.asInstanceOf[HostConnectionPool[Any]]
    logger.info(s"Looking up s3://${sourceBucket}/$sourceKey@$sourceVersion in $destRegion")
    headSourceFile(destRegion, credentialsProvider, sourceBucket, sourceKey, sourceVersion).flatMap({
      case Some(headInfo)=>
        logger.info(s"Got header info for s3://${sourceBucket}/$sourceKey@$sourceVersion in $destRegion")
        val parts = ImprovedLargeFileCopier.deriveParts(destBucket, destKey, headInfo)

        logger.info(s"s3://${sourceBucket}/$sourceKey@$sourceVersion - ${parts.length} parts")
        initiateMultipartUpload(destRegion, credentialsProvider, destBucket, destKey, headInfo)
          .flatMap(uploadId=>{
            logger.info(s"s3://${sourceBucket}/$sourceKey@$sourceVersion - initiated MP upload to s3://$destBucket/$destKey with ID $uploadId")
            sendPartCopies(destRegion, credentialsProvider, uploadId, parts, headInfo)
              .flatMap(completedParts=>{
                logger.info(s"${sourceBucket}/$sourceKey@$sourceVersion - Copied all parts, completing the upload")
                //send the complete-upload confirmation
                completeMultipartUpload(destRegion, credentialsProvider, destBucket, destKey, uploadId, completedParts)
              })
              .recoverWith({
                case err:Throwable=>  //if any error occurs ensure that the upload is aborted
                  logger.error(s"Copy to s3://$destBucket/$destKey failed: ${err.getMessage}", err)
                  abortMultipartUpload(destRegion, credentialsProvider, headInfo.bucket, headInfo.key, uploadId)
                  .flatMap(_=>Future.failed(err))
              })
              .map(completed=>{
                logger.info(s"${sourceBucket}/$sourceKey@$sourceVersion - completed upload with location ${completed.location}, etag ${completed.eTag}")
                completed
              })
        })
      case None=>
        logger.error(s"Can't copy s3://$sourceBucket/$sourceKey@${sourceVersion.getOrElse("LATEST")} because the source file does not exist")
        Future.failed(new RuntimeException(s"Source file did not exist"))
    })
  }
}
