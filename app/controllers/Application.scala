package controllers

import javax.inject.Inject
import play.api._
import play.api.mvc._

class Application @Inject() (cc:ControllerComponents) extends AbstractController(cc) {
  def index(path:String) = Action {
    Ok(views.html.index("Archive Hunter")("fake-cachebuster"))
  }

  def healthcheck = Action {
    //basic healthcheck endpoint, will extend later
    Ok("online")
  }
}