package controllers

import auth.{BearerTokenAuth, Security}

import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import java.time.ZonedDateTime
import java.util.Properties
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.cache.SyncCacheApi
import responses.{GenericErrorResponse, VersionInfoResponse}

@Singleton
class VersionController @Inject() (override val controllerComponents:ControllerComponents,
                                   override val config:Configuration,
                                   override val bearerTokenAuth:BearerTokenAuth,
                                   override val cache:SyncCacheApi)
  extends AbstractController(controllerComponents) with Security with Circe with ZonedDateTimeEncoder {

  protected def findData():Option[InputStream] = {
    val classVersion = getClass.getResourceAsStream("version.properties")
    if(classVersion!=null) return Some(classVersion)
    val fileVersion = new BufferedInputStream(new FileInputStream(new File("conf/version.properties")))
    if(fileVersion!=null) return Some(fileVersion)
    None
  }

  protected def getData()= {
    findData().flatMap(inputStream=>{
      try {
        val props = new Properties()
        props.load(inputStream)
        if (props.isEmpty) {
          None
        } else {
          Some(props)
        }
      } finally {
        if (inputStream != null) inputStream.close()
      }
    })
  }

  protected def marshalData(props:Properties):Either[String, VersionInfoResponse] = {
    try {
      Right(VersionInfoResponse(Option(props.getProperty("buildDate")).map(timeStr=>ZonedDateTime.parse(timeStr)),
        Option(props.getProperty("buildBranch")),
        Option(props.getProperty("buildNumber")).map(_.toInt)
      ))
    } catch {
      case ex:Throwable=>
        Left(ex.getMessage)
    }
  }

  def getInfo = IsAuthenticated { _=> _=>
    getData() match {
      case Some(props)=>
        marshalData(props) match {
          case Left(err)=>
            InternalServerError(GenericErrorResponse("error",err).asJson)
          case Right(response)=>
            Ok(response.asJson)
        }
      case None=>
        NotFound(GenericErrorResponse("error","No version information found").asJson)
    }
  }
}
