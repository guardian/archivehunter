package helpers

import akka.stream.alpakka.s3.ListBucketResultContents

import java.time.{ZoneId, ZonedDateTime}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.alpakka.s3.scaladsl._
import akka.stream.scaladsl._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ItemNotFound

import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class S3ToArchiveEntryFlow @Inject() (s3ClientMgr: S3ClientManager, config:Configuration, esClientManager:ESClientManager) extends GraphStage[FlowShape[ListBucketResultContents, ArchiveEntry]] {
  final val in:Inlet[ListBucketResultContents] = Inlet.create("S3ToArchiveEntry.in")
  final val out:Outlet[ArchiveEntry] = Outlet.create("S3ToArchiveEntry.out")

  override def shape: FlowShape[ListBucketResultContents, ArchiveEntry] = {
    FlowShape.of(in,out)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      //over-riding the element name to provide the region is a bit hacky but it works.
      val region = inheritedAttributes.nameOrDefault(config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1"))

      implicit val s3Client:AmazonS3 = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"),Some(region))
      private implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))
      private implicit val esClient = esClientManager.getClient()
      private val logger=Logger(getClass)

      logger.debug("initialised new instance")

      /**
        * returns an updated entry if there are significant differences
        * @param existingEntry
        * @param newEntry
        * @return
        */
      def updateIfNecessary(existingEntry:ArchiveEntry, newEntry:ArchiveEntry):Option[ArchiveEntry] = {
        val firstUpdate = if(existingEntry.last_modified.toLocalDateTime!=newEntry.last_modified.toLocalDateTime){
          logger.info(s"last_modified time updated on ${existingEntry.path} from ${existingEntry.last_modified.toLocalDateTime} to ${newEntry.last_modified.toLocalDateTime}")
          existingEntry.copy(last_modified = newEntry.last_modified)
        } else {
          existingEntry
        }

        val secondUpdate = if(existingEntry.etag!=newEntry.etag){
          logger.info(s"etag updated on ${existingEntry.location}")
          firstUpdate.copy(etag = newEntry.etag)
        } else {
          firstUpdate
        }

        val thirdUpdate = if(existingEntry.storageClass!=newEntry.storageClass){
          logger.info(s"storage class updated on ${existingEntry.location}")
          secondUpdate.copy(storageClass = newEntry.storageClass)
        } else {
          secondUpdate
        }

        if(thirdUpdate==existingEntry){
          logger.info(s"No differences on ${existingEntry.location}")
          None
        } else {
          logger.info(s"Updates detected on ${existingEntry.location}")
          Some(thirdUpdate)
        }
      }

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          logger.debug(s"got element $elem")
          try {
            //we need to do a metadata lookup to get the MIME type anyway, so we may as well just call out here.
            //it appears that you can't push() to a port from in a Future thread, so doing it the crappy way and blocking here.
            val mappedElem = ArchiveEntry.fromS3Sync(elem.bucketName, elem.key, region)
            logger.debug(s"Mapped $elem to $mappedElem")

            val maybeExistingEntry = ArchiveEntry.fromIndexFull(elem.bucketName, elem.key)
            val toUpdateFuture = maybeExistingEntry.map({
              case Right(existingEntry)=>
                logger.info(s"Found existing entry for s3://${elem.bucketName}/${elem.key} at ${existingEntry.id}")
                updateIfNecessary(existingEntry, mappedElem)
              case Left(ItemNotFound(itemId))=>
                logger.info(s"No existing item found for $itemId")
                Some(mappedElem)
              case Left(err)=>
                logger.error(s"Could not check existing archive entry: $err")
                None
            })

            Await.result(toUpdateFuture, 30 seconds) match {
              case Some(elemToUpdate)=>
                push(out, elemToUpdate)
              case None=>
                logger.info(s"Nothing to update on ${mappedElem.location
                }, grabbing next item")
                pull(in)
            }

          } catch {
            case ex:Throwable=>
              logger.error(s"Could not create ArchiveEntry: ", ex)
              failStage(ex)
          }
        }
      })

      setHandler(out, new AbstractOutHandler {
        override def onPull(): Unit = {
          logger.debug("pull from downstream")
          pull(in)
        }
      })
    }

}
