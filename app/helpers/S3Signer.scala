package helpers

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.{OffsetDateTime, ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter

import akka.http.scaladsl.model.HttpHeader.ParsingResult.{Error, Ok}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.ByteString
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.regions.Region
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** this trait implements logic for signing S3 requests over HTTP */
/* see https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html*/

trait S3Signer {
  protected val logger:Logger
  implicit val mat:Materializer
  implicit val ec:ExecutionContext

  val aws_compatible_date = DateTimeFormatter.ofPattern("uuuuMMdd")
  val aws_compatible_datetime = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
  protected def digestString(str:String) = {
    val checksummer = MessageDigest.getInstance("SHA-256")
    checksummer.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  protected def hmacString(key:String,str:String) = {
    val secretKeySpec = new SecretKeySpec(key.getBytes("UTF-8"),"SHA-256")
    val hmaccer = Mac.getInstance("HmacSHA256")
    hmaccer.init(secretKeySpec)
    hmaccer.doFinal(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  /**
    * splits down a standard query string into a map of key-value pairs
    * @param queryString
    * @return
    */
  protected def convertQueryString(queryString:Option[String]):Map[String,String] = {
    def makeTuple(str:String):(String,String) = {
      val parts = str.split("=")
      Tuple2(parts.head, parts(1))
    }

    queryString.map(_.split("&").map(el=>makeTuple(el)).toMap).getOrElse(Map())
  }

  protected def makeHttpHeader(key:String,value:String):HttpHeader = HttpHeader.parse(key, value) match {
    case Ok(result, errors)=>result
    case Error(errors)=>throw new RuntimeException(errors.toString)
  }

  protected def headersToMap(headers: Seq[HttpHeader]) = headers.map(head=>Tuple2(head.name, head.value)).toMap

  /**
    * Asynchronously signs the given [[HttpRequest]] object using the credentials provider given
    * @param req Incoming HttpRequest
    * @param region AWS region to access
    * @param serviceName service name
    * @param credsProvider AWSCredentialsProvider instance (or chain) that gives us credentials
    * @return a Future with the updated [[HttpRequest]]
    */
  def signHttpRequest(req:HttpRequest, region:Region, serviceName:String, credsProvider:AWSCredentialsProvider, timestamp:Option[OffsetDateTime]=None) = {
    import scala.collection.immutable.Seq
    val checksummer = MessageDigest.getInstance("SHA-256")
    val requestTime = timestamp.getOrElse(OffsetDateTime.now(ZoneOffset.UTC))

    val contentHashFuture = if(req.entity.isKnownEmpty()) {
      Future(ByteString(checksummer.digest("".getBytes("UTF-8"))))
    } else {
      req.entity.getDataBytes()
        .via(new ContentHashingFlow("SHA-256"))
        .runWith(Sink.reduce[ByteString](_.concat(_)), mat)
    }

    val contentHashHexFuture = contentHashFuture.map(bs=>bs.map("%02x".format(_)).mkString)

    val updatedHeadersFuture = contentHashHexFuture.map(hash=>
      req.headers ++ Seq(
        makeHttpHeader("x-amz-date",requestTime.format(aws_compatible_datetime)),
        makeHttpHeader("x-amz-content-sha256",hash)
      )
    )

    val canonStringFuture = updatedHeadersFuture.map(headers=> {
      val hash = contentHashHexFuture.value.get.get //this is safe, because updatedHeadersFuture is mapped from contentHashHexFuture; therefore if we got here, it succeeded.
      calculateCanonicalString(req.method.value, req.uri.path.toString(), convertQueryString(req.uri.rawQueryString), headersToMap(headers), Some(hash))
    })

    canonStringFuture.onComplete({
      case Success(str)=>logger.debug(s"canonicalString is $str")
      case Failure(err)=>logger.error(s"Canonical string failed", err)
    })

    val stringToSignFuture = canonStringFuture.map(cs=>stringToSign(region.getName, serviceName, cs, requestTime))

    stringToSignFuture.onComplete({
      case Success(str)=>logger.debug(s"stringToSign is $str")
      case Failure(err)=>logger.error(s"stringToSign failed", err)
    })
    val credentials = credsProvider.getCredentials
    val signingKeyResult = signingKey(credentials.getAWSSecretKey, serviceName, region.getName, requestTime)
    val sig = stringToSignFuture.map(sts=>finalSignature(signingKeyResult, sts))

    Future.sequence(Seq(sig, updatedHeadersFuture)).map(results=>{
      val finalSig = results.head.asInstanceOf[String]
      val signedHeaders = results(1).asInstanceOf[Seq[HttpHeader]]
      val signedHeadersString = signedHeaders.map(_.name().toLowerCase()).sorted.mkString(";")
      val finalHeaders =  signedHeaders ++ Seq(
        makeHttpHeader("Authorization", s"AWS4-HMAC-SHA256 Credential=${credentials.getAWSAccessKeyId}/${requestTime.format(aws_compatible_date)}/$region/$serviceName/aws4_request,SignedHeaders=$signedHeadersString,Signature=$finalSig")
      )

      logger.debug(s"Final headers are: $finalHeaders")
      req.copy(headers=finalHeaders)
    })
  }

  /**
    * Step one of the algorithm: calculate the canonical string
    * @param httpMethod
    * @param uriPath
    * @param uriQueryParams
    * @param headers
    * @param payloadHash
    * @return
    */
  protected def calculateCanonicalString(httpMethod:String, uriPath: String, uriQueryParams:Map[String,String],
                               headers:Map[String,String], payloadHash:Option[String]) = {
    val checksummer = MessageDigest.getInstance("SHA-256")

    val canonicalUrl = uriPath.split("/").map(section=>URLEncoder.encode(section,"UTF-8")).mkString("/")
    logger.debug(s"encodedUrl: $canonicalUrl")

    val canonicalQueryString = uriQueryParams.map(entry=>URLEncoder.encode(entry._1,"UTF-8")+"="+URLEncoder.encode(entry._2,"UTF-8")).mkString("&")
    logger.debug(s"canonicalQueryString: $canonicalQueryString")

    val updatedHeaders = if(headers.keys.exists(_=="x-amz-content-sha256")){
      headers
    } else {
      headers ++ "x-amz-content-sha256"->checksummer.digest("".getBytes("UTF-8")).map("%02x".format(_)).mkString
    }

    checksummer.reset()

    val canonicalHeaders = headers.keys.toList.sorted.map(header=>{
      header.toLowerCase + ":" + headers(header).trim
    }).mkString("\n") + "\n"
    logger.debug(s"canonicalHeaders: $canonicalHeaders")

    val signedHeaders = headers.keys.map(_.toLowerCase).toList.sorted.mkString(";")
    logger.debug(s"signedHeaders: $signedHeaders")

    val hashedPayload = payloadHash match {
      case Some(hexDigest)=>hexDigest
      case None=>checksummer.digest("".getBytes("UTF-8")).map("%02x".format(_)).mkString
    }
    logger.debug(s"hashedPayload: $hashedPayload")

    s"""$httpMethod
       |$canonicalUrl
       |$canonicalQueryString
       |$canonicalHeaders
       |$signedHeaders
       |$hashedPayload""".stripMargin
  }

  /**
    * Step two - create a string to sign
    * @param region AWS region name (string)
    * @param serviceName AWS service name (String)
    * @param canonicalRequestString Canonical request string as provided by `calculateCanonicalString`
    */
  protected def stringToSign(region:String, serviceName:String, canonicalRequestString: String, requestTime:OffsetDateTime) = {
    val checksummer = MessageDigest.getInstance("SHA-256")
    val timestamp = requestTime.format(aws_compatible_datetime)
    logger.debug(s"timestamp is $timestamp")
    val scope = s"${requestTime.format(aws_compatible_date)}/$region/$serviceName/aws4_request"
    logger.debug(s"scope is $scope")
    val canonRequestDigest = checksummer.digest(canonicalRequestString.getBytes("UTF-8")).map("%02x".format(_)).mkString
    logger.debug(s"Digest of canonical string is $canonRequestDigest")

    s"""AWS4-HMAC-SHA256
       |$timestamp
       |$scope
       |$canonRequestDigest""".stripMargin
  }

  /**
    * Step three - Calculate signing key
    */
  protected def signingKey(secretAccessKey:String, serviceName:String, awsRegion:String, requestTime:OffsetDateTime) = {
    val dateValue = requestTime.format(aws_compatible_date)
    logger.debug(s"dateValue is $dateValue")
    val dateKey = hmacString("AWS4" + secretAccessKey, dateValue)
    logger.debug(s"dateKey is $dateKey")
    val dateRegionKey = hmacString(dateKey, awsRegion)
    logger.debug(s"dateRegionKey is $dateRegionKey")
    val dateRegionServiceKey = hmacString(dateRegionKey, serviceName)
    logger.debug(s"dateRegionServiceKey is $dateRegionServiceKey")

    hmacString(dateRegionServiceKey, "aws4_request")
  }

  /**
    * Step four - use the signing key on the string to sign
    * @param signingKey provided from step three
    * @param stringToSign provided from step two
    * @return
    */
  protected def finalSignature(signingKey:String, stringToSign:String) = {
    hmacString(signingKey, stringToSign)
  }
}
