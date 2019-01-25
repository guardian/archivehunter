package controllers

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.Date

import akka.actor.{ActorRef, ActorSystem}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder
import com.amazonaws.services.cloudformation.model._
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ProxyFrameworkInstance, ProxyFrameworkInstanceDAO}
import com.typesafe.config.ConfigException.Generic
import helpers.InjectableRefresher
import javax.inject.{Inject, Named}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import io.circe.syntax._
import io.circe.generic.auto._
import requests.AddPFDeploymentRequest
import responses.{GenericErrorResponse, MultiResultResponse, ObjectListResponse, ProxyFrameworkDeploymentInfo}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ProxyFrameworkAdminController @Inject() (override val config:Configuration,
                                               override val controllerComponents:ControllerComponents,
                                               override val refresher:InjectableRefresher,
                                               override val wsClient:WSClient,
                                               proxyFrameworkInstanceDAO: ProxyFrameworkInstanceDAO,
                                               proxyFrameworkHelper: helpers.ProxyFramework)
                                              (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with Circe with PanDomainAuthActions with AdminsOnly {

  private val logger = Logger(getClass)
  implicit val ec:ExecutionContext = controllerComponents.executionContext

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")

  def existingDeployments = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {
      proxyFrameworkInstanceDAO.allRecords.map(result=>{
        val failures = result.collect({case Left(err)=>err})
        if(failures.nonEmpty){
          InternalServerError(GenericErrorResponse("db_error",failures.head.toString).asJson)
        } else {
          val records = result.collect({case Right(record)=>record})
          Ok(ObjectListResponse("ok","ProxyFrameworkInstance",records,records.length).asJson)
        }
      })
    }
  }

  def credentialsProvider(profileName:Option[String]=None) = new AWSCredentialsProviderChain(
    new ProfileCredentialsProvider(profileName.getOrElse("default")),
    new ContainerCredentialsProvider(),
    new InstanceProfileCredentialsProvider()
  )

  protected def getCfClient(region:String) =
    AmazonCloudFormationClientBuilder.standard()
      .withCredentials(credentialsProvider(awsProfile))
      .withRegion(region).build()
  /**
    * (asynchronously) scan cloudformation within the given region for deployments of the proxy framework
    * @param region String of the region to search
    * @return a Future containing a Sequence of StackSummary objects along with the region name
    */
  def scanRegionForDeployments(region:String):Future[(String,Try[Seq[StackSummary]])] = Future {
    val searchParam = config.getOptional[String]("proxyFramework.descriptionSearch").getOrElse("Proxying framework for ArchiveHunter")
    val cfClient = getCfClient(region)

    logger.info(s"Looking for deployments in region $region based on a template description of '$searchParam'")
    /**
      * recursively get stack information from CF
      * @param rq ListStacksRequest instance
      * @param continuationToken Optional continuation token, for recursion. Don't specify when calling
      * @param currentValues Initial set of StackSummary. Don't specify when calling
      * @return a Seq of StackSummaries containing info about relevant stacks
      */
    def getNextPage(rq:ListStacksRequest, continuationToken:Option[String]=None, currentValues:Seq[StackSummary]=Seq()):Seq[StackSummary] = {
      val finalRq = continuationToken match {
        case Some(tok)=>rq.withNextToken(tok)
        case None=>rq
      }

      logger.debug(s"getting next page of results, continuation token is $continuationToken")
      val result = cfClient.listStacks(finalRq)
      logger.debug(s"results page: ${result.getStackSummaries.asScala}")
      Option(result.getNextToken) match {
        case Some(tok)=>
          logger.debug(s"Got continuation token $tok, recursing...")
          getNextPage(rq, Some(tok), currentValues ++ result.getStackSummaries.asScala.filter(_.getTemplateDescription.startsWith(searchParam)))
        case None=>
          logger.debug(s"No continuation token, reached end of results.")
          val data = Option(result.getStackSummaries) match {
            case Some(list) =>
              list.asScala.filter(summ=>
                Option(summ.getTemplateDescription).isDefined && summ.getTemplateDescription.startsWith(searchParam)
              )
            case None => Seq()
          }
          currentValues ++ data
      }
    }

    logger.debug("Looking for stacks with CREATE_COMPLETE,UPDATE_COMPLETE or UPDATE_IN_PROGRESS")
    try {
      val baseRq = new ListStacksRequest().withStackStatusFilters(Seq("CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_IN_PROGRESS").asJavaCollection)
      val results = getNextPage(baseRq)
      logger.debug(s"Got final results $results")
      (region, Success(results))
    } catch {
      case err:Throwable=>
        logger.error(s"Could not list stacks from $region", err)
        (region, Failure(err))
    }
  }

  /**
    * endpoint that returns the known valid regions
    * @return
    */
  def getRegions = APIAuthAction { request=>
    Ok(ObjectListResponse("ok","regions",Regions.values().map(_.toString.replace("_","-").toLowerCase),Regions.values().length).asJson)
  }

  def convertJavaDate(date:Date) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(date.getTime),ZoneId.of("UTC"))
  /**
    * scans for anything that looks like a deployment of the Proxy Framework in the running AWS account
    * @return
    */
  def lookupPotentialDeployments = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {
      val lookupFutures = Regions.values().map(rgn=>scanRegionForDeployments(rgn.getName)).toSeq

      val resultsFuture = Future.sequence(lookupFutures).map(resultList=>{
        resultList.map(resultTuple=>{
          resultTuple._2 match {
            case Success(summarySeq)=>
              Right(summarySeq.map(summ=>
                ProxyFrameworkDeploymentInfo(
                  resultTuple._1, summ.getStackId,summ.getStackName,summ.getStackStatus, summ.getTemplateDescription,convertJavaDate(summ.getCreationTime))
                ))
            case Failure(err)=>
              Left((resultTuple._1, err.toString))
          }
        })
      })

      resultsFuture.map(results=>{
        val successes = results.collect({ case Right(info) => info })
        val failures = results.collect({ case Left(err) => err })

        val statusString = if(successes.isEmpty){
          "failure"
        } else if(failures.isEmpty){
          "success"
        } else {
          "partial"
        }

        Ok(MultiResultResponse(statusString, "proxyFrameworkDeploymentInfo", successes, failures).asJson)
      })
    }
  }

  def addDeployment = APIAuthAction.async(circe.json(2048)) { request=>
    adminsOnlyAsync(request) {
      request.body.as[AddPFDeploymentRequest].fold(
        err=>Future(BadRequest(GenericErrorResponse("bad_request",err.toString).asJson)),
        clientRequest=> {
          val client = getCfClient(clientRequest.region)
          val rq = new DescribeStacksRequest().withStackName(clientRequest.stackName)
          val result = client.describeStacks(rq)
          val stacks = result.getStacks.asScala
          if(stacks.isEmpty){
            Future(NotFound(GenericErrorResponse("not_found","No stack by that name in that region").asJson))
          } else if(stacks.length>1){
            Future(BadRequest(GenericErrorResponse("too_many","Multiple stacks found by that name").asJson))
          } else {
            ProxyFrameworkInstance.fromStackSummary(clientRequest.region, stacks.head) match {
              case Some(rec) =>
                //setupDeployment saves the created record to the DB and performs subscriptions/security policy updates
                proxyFrameworkHelper.setupDeployment(rec).map({
                  case Success(results) =>
                    Ok(GenericErrorResponse("ok", "Record saved").asJson)
                  case Failure(err) =>
                    InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
                })
              case None =>
                Future(BadRequest(GenericErrorResponse("invalid_request", "Stack did not have the right outputs defined").asJson))
            }
          }
        }.recover({
          case err:Throwable=>
            logger.error("Could not add new deployment from CF stack: ", err)
            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
        })
      )
    }
  }

  def addDeploymentDirect = APIAuthAction.async(circe.json(2048)) { request=>
    adminsOnlyAsync(request) {
      request.body.as[ProxyFrameworkInstance].fold(
        err=>Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
        rec=>proxyFrameworkInstanceDAO.recordsForRegion(rec.region).flatMap(results=>{
          if(results.nonEmpty){
            Future(Conflict(GenericErrorResponse("already_exists", "A record already exists for this region").asJson))
          } else {
            proxyFrameworkInstanceDAO.put(rec).map({
              case None=>
                Ok(GenericErrorResponse("ok","Record saved").asJson)
              case Some(Right(updatedRecord))=>
                Ok(GenericErrorResponse("ok","Record saved").asJson)
              case Some(Left(err))=>
                InternalServerError(GenericErrorResponse("db_error",err.toString).asJson)
            })
          }
        })
      )
    }
  }

  def removeDeployment(forRegion:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {
      proxyFrameworkInstanceDAO.recordsForRegion(forRegion).flatMap(results=>{
        val failures = results.collect({case Left(err)=>err})
        if(failures.nonEmpty){
          logger.error(s"Could not locate deployment to remove: $failures")
          Future(InternalServerError(GenericErrorResponse("error", failures.head.toString).asJson))
        } else {
          val frameworks = results.collect({case Right(pt)=>pt})
          if(frameworks.isEmpty){
            logger.error(s"Could not find any frameworks for region $forRegion")
            Future(NotFound(GenericErrorResponse("error", s"Could not find any frameworks for region $forRegion").asJson))
          } else {
            logger.info(s"Attempting to detach framework instance ${frameworks.head}...")
            proxyFrameworkHelper.detachFramework(frameworks.head).flatMap({
              case Success(_)=>
                logger.info(s"Detach succeeded. Deleting reference...")
                proxyFrameworkInstanceDAO.remove(forRegion)
                  .map(result=> {
                    logger.info("Reference deleted from database; remove deployment complete")
                    Ok(GenericErrorResponse("ok", "Record deleted").asJson)
                  })
                  .recoverWith({
                    case err:Throwable=>
                      logger.error("Could not remove reference from database: ", err)
                      Future(InternalServerError(GenericErrorResponse("db_error",err.toString).asJson))
                  })
            })
          }
        }
      })

    }
  }
}
