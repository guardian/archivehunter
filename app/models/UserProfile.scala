package models

object UserProfileField extends Enumeration {
  val IS_ADMIN,VISIBLE_COLLECTIONS,ALL_COLLECTIONS,PRODUCTION_OFFICE,DEPARTMENT,
  PER_RESTORE_QUOTA,ROLLING_QUOTA,ADMIN_APPROVAL_QUOTA,ADMIN_ROLLING_APPROVAL_QUOTA = Value
}

/**
  * this case class represents a user profile
  * @param userEmail gmail address. This is used as a primary key, or ID.
  * @param isAdmin boolean indicating whether this user has admin rights. If false, some operations are denied
  * @param visibleCollections list of collections that the user is allowed to see
  * @param allCollectionsVisible boolean indicating whether to ignore the `visibleCollections` list and show everything.
  * @param perRestoreQuota maximum size of restore that this user is able to perform, in Mb. If not present then the user is not allowed to restore.
  */
case class UserProfile (userEmail:String, isAdmin:Boolean,
                        visibleCollections:Seq[String], allCollectionsVisible: Boolean,
                        productionOffice: Option[String], department:Option[String],
                        perRestoreQuota:Option[Long], rollingRestoreQuota:Option[Long],
                        adminAuthQuota:Option[Long], adminRollingAuthQuota:Option[Long])