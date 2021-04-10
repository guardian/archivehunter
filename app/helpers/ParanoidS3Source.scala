package helpers

import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, model}
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.alpakka.s3.scaladsl.ListBucketResultContents
import akka.stream.stage.{AbstractOutHandler, GraphStage, GraphStageLogic}
import akka.http.scaladsl.model.HttpHeader.ParsingResult._
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.regions.Region

import scala.collection.immutable.Seq
import play.api.Logger
import akka.http.scaladsl.model.HttpProtocol

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Source which performs Listbucket requests and outputs the XML as a String to be sanitised
  * @param bucketName bucket to scan
  * @param region AWS region that the bucket lives in
  * @param actorSystem implicitly provided ActorSystem for akka http
  */
class ParanoidS3Source(bucketName:String, region:Region, credsProvider: AWSCredentialsProvider)(implicit actorSystem:ActorSystem)
  extends GraphStage[SourceShape[ByteString]] with S3Signer {
  private val out:Outlet[ByteString] = Outlet.create("ParanoidS3Source.out")

  implicit val mat:Materializer = ActorMaterializer()
  implicit val ec:ExecutionContext = actorSystem.dispatcher
  override def shape: SourceShape[ByteString] = SourceShape.of(out)

  val logger = Logger(getClass)
  /**
    * extract given entries from the XML manually. This is necessary as we are in "paranoid mode", and can't
    * rely on the XML being valid.
    * @param paramsToFind Seq[String] giving the parameters to find
    * @param body ByteString
    * @return a Map, containing each `paramToFind` pointing to an Option which has the string value, if present.
    *         NOTE: this assumes that the keys and data to extract are both UTF-8 compatible (but the overall document can break)
    */
  def findParams(paramsToFind:Seq[String], body:ByteString):Map[String,Option[String]] = {
    val paramsToFindBytes = paramsToFind.map(ByteString(_))

    def captureString(toFind:ByteString, haystack:ByteString,n:Int):Option[ByteString] = {
      //now capture everything up to the next < character
      for(i<-n+toFind.length to haystack.length){
        if(haystack(i)=="<".charAt(0).toByte){
          val result = haystack.slice(n+toFind.length+1,i)
          return Some(result)
        }
      }
      None
    }

    def locateString(toFind:ByteString, haystack:ByteString):Option[Int] = {
      for(n<-0 to haystack.length-toFind.length){
        val matcher = haystack.slice(n, n+toFind.length)
        if(matcher==toFind){
          return Some(n)
        }
      }
      None
    }

    def findParamFuture(toFind: ByteString, haystack:ByteString):Future[Tuple2[ByteString, Option[ByteString]]] = Future {
      locateString(toFind, haystack) match {
        case Some(location)=>Tuple2(toFind, captureString(toFind, haystack, location))
        case None=>Tuple2(toFind,None)
      }
    }

    Await.result(Future.sequence(paramsToFindBytes.map(findParamFuture(_,body)))
        .map(_.map(tuple=>Tuple2(tuple._1.utf8String, tuple._2.map(_.utf8String))))
      .map(_.toMap)
      , 10.seconds)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val logger=Logger(getClass)

    var onPage:Int = 0
    var continuationToken:Option[String] = None

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        val headerSequence = Seq(
          HttpHeader.parse("Host", s"$bucketName.s3.${region.getName}.amazonaws.com"),
          HttpHeader.parse("Date", OffsetDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)),
        ).map({
          case Ok(header, errors)=>header
          case Error(err)=>throw new RuntimeException(err.toString)
        })

        val baseParams = "list-type=2&encoding-type=url"
        //val baseParams = "delimiter=/&encoding-type=url&prefix"
        val qParams = continuationToken match {
          case Some(token)=>
            logger.debug(s"continuation token is $token")
            baseParams + s"&continuation-token=${URLEncoder.encode(token, "UTF-8")}"
          case None=>
            logger.debug("no continuation token")
            baseParams
        }

        val request = HttpRequest(HttpMethods.GET,
          Uri(s"https://$bucketName.s3.${region.getName}.amazonaws.com?$qParams"),
          headerSequence)

        val signedRequest = Await.result(signHttpRequest(request, region,"s3", credsProvider), 10 seconds)
        logger.debug(s"Signed request is ${signedRequest.toString()}")
        val response = Await.result(Http().singleRequest(signedRequest), 10 seconds)

        //we are in paranoid mode, so can't assume that this is valid xml (yet). So, we buffer the content and manually scan for
        //the continuationToken and isTruncated flags we require.
        val body = Await.result(response.entity.getDataBytes().runWith(Sink.fold[ByteString,ByteString](ByteString.empty)(_.concat(_)), mat), 10 seconds)
        push(out, body)
        val flags:Map[String,Option[String]] = findParams(Seq("NextContinuationToken","KeyCount","IsTruncated"), body)

        flags("IsTruncated") match {
          case Some(flag)=>
            if(flag=="true"){
              continuationToken = flags("NextContinuationToken")
            } else {
              completeStage()
            }
          case None=>completeStage()
        }
      }

    })
  }
}
