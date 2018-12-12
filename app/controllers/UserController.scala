package controllers

import helpers.InjectableRefresher
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectCreatedResponse, ObjectGetResponse, ObjectListResponse}
import io.circe.syntax._
import io.circe.generic.auto._
import models.{UserProfile, UserProfileDAO, UserProfileField}
import requests.{FieldUpdateOperation, UserProfileFieldUpdate, UserProfileFieldUpdateEncoder}

import scala.concurrent.Future

@Singleton
class UserController @Inject()(override val controllerComponents:ControllerComponents,
                               override val wsClient: WSClient,
                               override val config: Configuration,
                               override val refresher:InjectableRefresher,
                               userProfileDAO:UserProfileDAO)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with AdminsOnly with UserProfileFieldUpdateEncoder with Circe {
  implicit val ec = controllerComponents.executionContext

  private val logger = Logger(getClass)
  def loginStatus = APIAuthAction { request =>
    val user = request.user
    Ok(user.toJson)
  }

  def allUsers = APIAuthAction.async {request=>
    adminsOnlyAsync(request) {
      userProfileDAO.allUsers().map(resultList=>{
        val errors = resultList.collect({case Left(err)=>err})
        if(errors.nonEmpty){
          InternalServerError(GenericErrorResponse("db_error", errors.map(_.toString).mkString(",")).asJson)
        } else {
          Ok(ObjectListResponse("ok","user",resultList.collect({case Right(up)=>up}), resultList.length).asJson)
        }
      })
    }
  }

  /**
    * converts the singleString field in the request to a boolean value, or returns an error
    * @param rq [[UserProfileFieldUpdate]] request
    * @return either the boolean value or an error string
    */
  protected def getSingleBoolValue(rq:UserProfileFieldUpdate):Either[String,Boolean] =
    rq.stringValue.map(_.toLowerCase) match {
      case None=>Left("stringValue must be specified")
      case Some("true")=>Right(true)
      case Some("yes")=>Right(true)
      case Some("allow")=>Right(true)
      case Some("false")=>Right(false)
      case Some("no")=>Right(false)
      case Some("deny")=>Right(false)
      case Some(otherStr)=>Left(s"$otherStr is not a recognised value. Try 'true' or 'false'.")
    }


  /**
    * updates an existing list with the listValue field in the request, based on the `operation` field of the request
    * (overwrite, add, remove)
    * @param rq [[UserProfileFieldUpdate]] request
    * @param existingList existing list to update
    * @return either a new list value or an error string
    */
  protected def updateStringList(rq:UserProfileFieldUpdate, existingList:Seq[String]):Either[String, Seq[String]] =
    rq.listValue match {
      case None=>Left("listValue must be specified")
      case Some(updates)=>
        rq.operation match {
          case FieldUpdateOperation.OP_OVERWRITE=>
            Right(updates)
          case FieldUpdateOperation.OP_ADD=>
            //don't duplicate items.  If an item to add is already in the existingList, drop it.
            val filteredUpdates = updates.filter(update=> !existingList.contains(update))
            Right(existingList ++ filteredUpdates)
          case FieldUpdateOperation.OP_REMOVE=>
            Right(existingList.filter(entry=> !updates.contains(entry)))
        }
    }


  /**
    * tries to perform the actions requested in the [[UserProfileFieldUpdate]] object
    * @param originalProfile [[UserProfile]] object to update
    * @param rq [[UserProfileFieldUpdate]] object containing instructions for what to update
    * @return either an error string or the updated UserProfile (unsaved)
    */
  def performUpdate(originalProfile:UserProfile, rq:UserProfileFieldUpdate):Either[String, UserProfile] = {
    rq.fieldName match {
      case UserProfileField.IS_ADMIN=>
        getSingleBoolValue(rq).map(newValue=>originalProfile.copy(isAdmin = newValue))
      case UserProfileField.ALL_COLLECTIONS=>
        getSingleBoolValue(rq).map(newValue=>originalProfile.copy(allCollectionsVisible = newValue))
      case UserProfileField.VISIBLE_COLLECTIONS=>
        updateStringList(rq, originalProfile.visibleCollections).map(newValue=>originalProfile.copy(visibleCollections = newValue))
    }
  }

  /**
    * handle a frontend request to update a user profile
    * @return
    */
  def updateUserProfileField = APIAuthAction.async(circe.json(2048)) {request=>
    adminsOnlyAsync(request) {
      request.body.as[UserProfileFieldUpdate] match {
        case Left(err)=>
          logger.error(err.toString)
          Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson))
        case Right(updateRq)=>
          userProfileDAO.userProfileForEmail(updateRq.user).flatMap({
            case None=>
              Future(BadRequest(GenericErrorResponse("not_found", s"user ${updateRq.user} not found").asJson))
            case Some(Left(err))=>
              logger.error(err.toString)
              Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
            case Some(Right(originalProfile))=>
              performUpdate(originalProfile, updateRq) match {
                case Right(updatedProfile) =>
                  logger.info(s"updatedProfile is $updatedProfile")
                  userProfileDAO.put(updatedProfile).map({
                    case None =>
                      Ok(ObjectGetResponse("updated","profile",updatedProfile).asJson)
                    case Some(Left(err)) =>
                      InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
                    case Some(Right(previousValue))=>
                      Ok(ObjectGetResponse("updated","userProfile", updatedProfile).asJson)
                  })
                case Left(err)=>
                  Future(BadRequest(GenericErrorResponse("bad_request", err).asJson))
              }
          })
      }

    }
  }

  def myProfile = APIAuthAction {request=>
    userProfileFromSession(request.session) match {
      case Some(Left(err))=>
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      case Some(Right(profile))=>
        Ok(ObjectGetResponse("ok","userProfile",profile).asJson)
      case None=>
        NotFound(GenericErrorResponse("error","not found").asJson)
    }
  }
}
