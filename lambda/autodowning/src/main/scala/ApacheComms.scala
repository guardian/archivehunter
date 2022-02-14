import com.amazonaws.services.lambda.runtime.LambdaLogger
import io.circe.Decoder
import models.{AkkaMember, AkkaMembersResponse, UriDecoder}
import org.apache.http.{HttpEntity, NameValuePair}
import org.apache.http.client.methods.{HttpGet, HttpPut}
import org.apache.http.impl.client.HttpClients
import io.circe.syntax._
import io.circe.generic.auto._
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.message.BasicNameValuePair

import java.nio.charset.StandardCharsets
import java.util
import scala.util.{Failure, Success, Try}

/**
  * Non-Akka implementation of the comms interface
  */
class ApacheComms(contactAddress:String, contactPort:Int) extends UriDecoder {
  import models.EnhancedLambdaLogger._

  private val httpClient = Try { HttpClients.createDefault() }

  def consumeResponseEntity(e:HttpEntity) = {
    Try { e.getContent } match {
      case Success(inputStream)=>
        try {
          Success(inputStream.readAllBytes())
        } catch {
          case err:Throwable=>Failure(err)
        } finally {
          inputStream.close()
        }
      case Failure(err)=> Failure(err)
    }
  }

  def consumeResponseEntityAsString(e:HttpEntity) = consumeResponseEntity(e).flatMap(bytes=>Try { new String(bytes, StandardCharsets.UTF_8) })

  def consumeResponseEntityAs[T:Decoder](e:HttpEntity)(implicit logger:LambdaLogger) = consumeResponseEntityAsString(e).flatMap(stringContent=>{
      io.circe.parser
        .parse(stringContent)
        .flatMap(_.as[T]) match {
        case Right(content)=>Success(content)
        case Left(err)=>
          logger.error(s"Could not parse raw content $stringContent: $err")
          Failure(new RuntimeException(err.toString))
      }
  })

  def getNodes()(implicit logger:LambdaLogger):Try[Seq[AkkaMember]] = {
    if(httpClient.isFailure) return Failure(httpClient.failed.get)
    val client = httpClient.get
    logger.debug(s"address is http://$contactAddress:$contactPort/cluster/members")

    val request = new HttpGet(s"http://$contactAddress:$contactPort/cluster/members")

    val results = for {
      response <- Try { client.execute(request) }
      content <- consumeResponseEntityAs[AkkaMembersResponse](response.getEntity)
    } yield (response.getStatusLine, content)

    results.flatMap(tuple=>{
      val statusLine = tuple._1
      val content = tuple._2
      logger.debug(s"Got response ${statusLine.getStatusCode} ${statusLine.getReasonPhrase} from server")

      if(statusLine.getStatusCode==200) { //with the construct above I expect it will _always_ be 200 but there is no harm in double-checking
        Success(content.members)
      } else {
        Failure(new RuntimeException(s"Got an unexpected response code ${statusLine.getStatusCode}"))
      }
    })
  }

  def downAkkaNode(node:AkkaMember)(implicit logger:LambdaLogger):Try[Boolean] = {
    if(httpClient.isFailure) return Failure(httpClient.failed.get)
    val client = httpClient.get
    val addr = s"http://$contactAddress:$contactPort/cluster/members/${node.node.toString}"
    logger.debug(s"address is $addr")

    val nvps = new util.ArrayList[NameValuePair]()
    nvps.add(new BasicNameValuePair("operation","down"))
    val entity = new UrlEncodedFormEntity(nvps)
    val request = new HttpPut(addr)
    request.setEntity(entity)

    val results = for {
      response <- Try { client.execute(request) }
      content <- consumeResponseEntityAsString(response.getEntity)
    } yield (response.getStatusLine, content)

    results.flatMap(tuple=>{
      val statusLine = tuple._1
      val content = tuple._2
      logger.debug(s"Downing request got response ${statusLine.getStatusCode} ${statusLine.getReasonPhrase} from server")
      logger.debug(s"Downing request replied '$content'")

      if(statusLine.getStatusCode==200) {
        Success(true)
      } else {
        Failure(new RuntimeException(s"Unexpected repsonse ${statusLine.getStatusCode} from downing request"))
      }
    })
  }
}
