package services

import java.io.ByteArrayInputStream

import akka.actor.{Actor, ActorSystem}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import javax.inject.Inject
import play.api.Configuration

import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.xml.parsing
import scala.xml.parsing.ConstructingParser
import scala.collection.JavaConverters._

object XmpMetadataExtractor {
  case class PerformExtraction(path:String, bucket:String)
}

class XmpMetadataExtractor @Inject() (config:Configuration, s3ClientManager: S3ClientManager)(implicit system:ActorSystem) extends Actor {
  implicit val mat:Materializer = ActorMaterializer.create(system)
  implicit val ec:ExecutionContext = system.getDispatcher

  val s3client = s3ClientManager.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))

  /**
    * stream in and parse the given document.  We're assuming that it's small enough to fit in memory.
    * @param path path within bucket to download from
    * @param bucket bucket to download from
    * @return a Future, containing an XML document if successful.
    */
  def streamInFile(path:String, bucket:String) = {
    val (source, metadataFuture) = s3client.download(bucket,path)
    val bytesFuture = source.toMat(Sink.fold(ByteString.empty)((acc,elem)=>acc.concat(elem)))(Keep.right).run()

    bytesFuture.map(byteString=>{
      val parser = ConstructingParser.fromSource(Source.fromInputStream(new ByteArrayInputStream(byteString.toArray[Byte])), preserveWS = false)
      parser.document()
    })
  }
}
