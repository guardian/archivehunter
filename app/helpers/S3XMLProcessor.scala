package helpers

import akka.stream.alpakka.s3.ListBucketResultContents

import java.net.URLDecoder
import java.time.Instant
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import akka.util.ByteString

import scala.xml.pull.XMLEventReader
import play.api.Logger

import scala.collection.mutable
import scala.io.Source
import scala.util.{Failure, Success, Try}
import scala.xml.pull._

class S3XMLProcessor (skipZeroLength:Boolean=true) extends GraphStage[FlowShape[ByteString,ListBucketResultContents]]{
  private val in:Inlet[ByteString] = Inlet.create("S3XMLProcessor.in")
  private val out:Outlet[ListBucketResultContents] = Outlet.create("S3XMLProcessor.out")

  private val logger=Logger(getClass)

  override def shape: FlowShape[ByteString, ListBucketResultContents] = FlowShape.of(in, out)

  def makeProperPath(str: String):String = {
    val decoded = str.split("/").map(URLDecoder.decode(_, "UTF-8")).mkString("/")
    if (decoded.slice(0, 1) == "/") {
      decoded.substring(1)
    } else {
      decoded
    }
    decoded
  }

  /**
    * generate a ListBucketContentsResult based on a Map of content from the raw S3 XML output
    * @param captures Map of (key->value) corresponding to the <Contents> structure of the S3 cml
    * @param bucketName bucket name that this relates to
    * @return a ListBucketContents result if we could coerce the map into its structure, or None (with an error output via the logger)
    */
  def listBucketResultFor(captures: Map[String, String], bucketName:String): Option[ListBucketResultContents] = try {
    val lastModTime = Instant.parse(captures("LastModified"))
    Some(ListBucketResultContents(bucketName, makeProperPath(captures("Key")), captures("ETag"), captures("Size").toLong, lastModTime, captures("StorageClass")))
  } catch {
    case ex:Throwable=>
      logger.error(s"Could not convert $captures into ListbucketResultContents: ", ex)
      None
  }

  private def captureFromXml(xml:XMLEventReader, currNode:List[String],allCaptures:Map[String,String]):Map[String,String] =
    if(xml.hasNext){
      xml.next() match {
        case EvElemStart(_, label, attrs, scope)=>
          logger.debug(s"\telem start: $label")
          captureFromXml(xml, label :: currNode, allCaptures)
        case EvText(text)=>
          if(currNode.nonEmpty) {
            logger.debug(s"\ttext: $text, currentCapture: ${currNode.head}")

            val updatedText = allCaptures.get(currNode.head) match {
              case Some(existingText) => existingText + text
              case None => text
            }
            val updatedCaptures = allCaptures ++ Map(currNode.head -> updatedText)
            logger.debug(updatedCaptures.toString)
            captureFromXml(xml, currNode, updatedCaptures)
          } else {
            captureFromXml(xml, currNode, allCaptures)
          }
        case EvElemEnd(_, label)=>
          logger.debug(s"\telem end: $label")
          if(currNode.isEmpty){
            allCaptures
          } else {
            captureFromXml(xml, currNode.tail, allCaptures)
          }
        case _=>
          captureFromXml(xml, currNode, allCaptures)
      }
    } else {
      allCaptures
    }


  def parseContentNode(xml:XMLEventReader, bucketName:String):Option[ListBucketResultContents] = {
    val allCaptures = captureFromXml(xml, List.empty, Map.empty)
    listBucketResultFor(allCaptures, bucketName)
  }

  def parseDoc(xml:XMLEventReader)(cb:Either[S3Error, ListBucketResultContents]=>Unit): Unit = {
    def loop(currNode: List[String], bucketName:String, inBucketName:Boolean=false): Unit = {
      logger.debug(s"in loop: $currNode")
      logger.debug(s"xml.hasNext: ${xml.hasNext}")
      if(xml.hasNext){
        xml.next() match {
          case EvElemStart(prefix, label, attrs, scope)=>
            logger.debug(s"start element: $label")
            if(label=="Contents"){
              logger.debug("Got contents")
              parseContentNode(xml, bucketName) match {
                case Some(result)=>
                  logger.debug(s"Got $result")
                  cb(Right(result))
                case None=>logger.error(s"Could not build ListBucketResult")
              }
            } else if(label=="Error"){
              val errorData = captureFromXml(xml, List.empty, Map.empty)
              S3Error.fromMap(errorData) match {
                case Success(err)=>cb(Left(err))
                case Failure(excp)=>
                  logger.error("Could not generate error entity: ", excp)
              }
            }

            if(label=="Name"){
              loop(label::currNode, bucketName, inBucketName = true)
            } else {
              loop(label::currNode, bucketName)
            }

          case EvElemEnd(prefix, label)=>
            logger.debug(s"end element: $label")
            if(label=="Bucket"){
              loop(currNode.tail, bucketName)
            } else {
              loop(currNode.tail, bucketName, inBucketName)
            }
          case EvText(text)=>
            logger.debug(s"text: '$text'")
            if(inBucketName){
              logger.debug(s"Got bucketname $text")
              loop(currNode, bucketName + text.trim, inBucketName)
            } else {
              loop(currNode, bucketName, inBucketName)
            }

          case EvEntityRef(entity)=>
            logger.debug(s"entity: $entity")
            loop(currNode, bucketName, inBucketName)
          case _=>
            logger.debug("Got nothing")
            loop(currNode, bucketName, inBucketName)
        }
      }
    }
    loop(List.empty, "")
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val logger = Logger(getClass)

    var entriesQueue:mutable.Queue[ListBucketResultContents] = mutable.Queue()

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        //elem is an XML document, that can't necessarily be trusted...
        logger.info("xml document:")
        logger.info(elem.utf8String)

        val xml = new XMLEventReader(Source.fromBytes(elem.toByteBuffer.array(), "UTF-8"))
        parseDoc(xml)({
          case Right(entry)=>
            if(skipZeroLength){
              if(entry.size>0) entriesQueue.enqueue(entry)
            } else {
              entriesQueue.enqueue(entry)
            }
          case Left(s3error)=>
            logger.error(s"Got an error from S3: ${s3error.toString}")
            failStage(new RuntimeException(s3error.toString))
        })
        if(entriesQueue.nonEmpty){
          push(out, entriesQueue.dequeue())
        } else {
          completeStage()
        }
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        logger.debug("pull from downstream")
        if(entriesQueue.nonEmpty){
          push(out, entriesQueue.dequeue())
        } else {
          pull(in)
        }
      }
    })
  }
}
