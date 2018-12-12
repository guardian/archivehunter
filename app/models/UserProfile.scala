package models

case class UserProfile (userEmail:String, isAdmin:Boolean, visibleCollections:Seq[String], allCollectionsVisible: Boolean)