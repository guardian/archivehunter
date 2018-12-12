package models

object UserProfileField extends Enumeration {
  val IS_ADMIN,VISIBLE_COLLECTIONS,ALL_COLLECTIONS = Value
}

/**
  * this case class represents a user profile
  * @param userEmail gmail address. This is used as a primary key, or ID.
  * @param isAdmin boolean indicating whether this user has admin rights. If false, some operations are denied
  * @param visibleCollections list of collections that the user is allowed to see
  * @param allCollectionsVisible boolean indicating whether to ignore the `visibleCollections` list and show everything.
  */
case class UserProfile (userEmail:String, isAdmin:Boolean, visibleCollections:Seq[String], allCollectionsVisible: Boolean)