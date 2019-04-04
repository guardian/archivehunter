package StreamComponents

import akka.stream.{Attributes, Inlet, Outlet, UniformFanOutShape}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveHunterConfiguration}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import javax.inject.Inject

import scala.annotation.switch

class IsGlacierBranch @Inject() (config:ArchiveHunterConfiguration, s3ClientMgr: S3ClientManager) extends GraphStage[UniformFanOutShape[ArchiveEntry,ArchiveEntry]]{
  final val in:Inlet[ArchiveEntry] = Inlet.create("IsGlacierBranch.in")
  final val outYes:Outlet[ArchiveEntry] = Outlet.create("IsGlacierBranch.yes")
  final val outNo:Outlet[ArchiveEntry] = Outlet.create("IsGlacierBranch.no")

  override def shape: UniformFanOutShape[ArchiveEntry, ArchiveEntry] = UniformFanOutShape(in, outYes, outNo)

  private val profileName = config.getOptional[String]("externalData.awsProfile")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val s3Client = s3ClientMgr.getClient(profileName)

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        try {
          val result = s3Client.getObjectMetadata(elem.bucket, elem.path)

          (result.getStorageClass: @switch) match {
            case "STANDARD" =>
              println(s"${elem.bucket}/${elem.path} is STANDARD")
              push(outNo, elem)
            case null =>
              println(s"${elem.bucket}/${elem.path} is STANDARD with no reply")
              push(outNo, elem)
            case "STANDARD_IA" =>
              println(s"${elem.bucket}/${elem.path} is IA")
              push(outNo, elem)
            case "REDUCED_REDUNDANCY" =>
              println(s"${elem.bucket}/${elem.path} is RR")
              push(outNo, elem)
            case "OneZoneInfrequentAccess" =>
              println(s"${elem.bucket}/${elem.path} is one-zone IA")
              push(outNo, elem)
            case "INTELLIGENT_TIERING" =>
              println(s"${elem.bucket}/${elem.path} is in intelligent tiering")
              push(outNo, elem)
            case "GLACIER" =>
              println(s"${elem.bucket}/${elem.path} is in Glacier")
              push(outYes, elem)
            case "DEEP_ARCHIVE" =>
              println(s"${elem.bucket}/${elem.path} is in Glacier DEEP")
              push(outYes, elem)
            case _ =>
              println(s"ERROR: Did not recognise storage class ${result.getStorageClass} for ${elem.bucket}/${elem.path}")
              throw new RuntimeException("Unrecognised storage class")
          }
        } catch {
          case ex:AmazonS3Exception=>
            println(s"WARNING: Could not process ${elem.bucket}/${elem.path}: $ex")
            pull(in)
        }
      }
    })

    setHandler(outYes, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(!hasBeenPulled(in)) pull(in)
      }
    })

    setHandler(outNo, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(!hasBeenPulled(in)) pull(in)
      }
    })
  }
}
