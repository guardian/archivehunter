package responses

import com.nimbusds.jwt.JWTClaimsSet
import auth.ClaimsSetExtensions._

object UserResponse extends ((String,String,String,Option[String],Boolean)=>UserResponse) {
  def fromClaims(claims:JWTClaimsSet, isAdmin:Boolean):UserResponse = {
    new UserResponse(
      claims.scalaSafeGetClaim("first_name").getOrElse(""),
      claims.scalaSafeGetClaim("last_name").getOrElse(""),
      claims.getEmail.getOrElse(""),
      None,
      isAdmin
    )
  }
}

case class UserResponse (firstName: String, lastName: String, email: String, avatarUrl: Option[String], isAdmin: Boolean)
