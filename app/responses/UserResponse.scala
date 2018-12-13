package responses

object UserResponse extends ((String,String,String,Option[String],Boolean)=>UserResponse) {
  def fromUser(user:com.gu.pandomainauth.model.User, isAdmin:Boolean):UserResponse = {
    new UserResponse(user.firstName, user.lastName, user.email, user.avatarUrl, isAdmin)
  }
}
case class UserResponse (firstName: String, lastName: String, email: String, avatarUrl: Option[String], isAdmin: Boolean)
