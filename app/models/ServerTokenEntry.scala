package models

import java.time.{Instant, ZonedDateTime}

case class ServerTokenEntry (value:String, createdAt:ZonedDateTime, expiry:Option[ZonedDateTime], uses:Int, expired:Boolean, associatedId:Option[String]) {
  /**
    * return an updated version of the token with the expired flag set if expiry time is passed. If expiry time is not passed same object
    * is returned
    * @return a ServerTokenEntry
    */
  def updateCheckExpired(maxUses:Option[Int]=None):ServerTokenEntry = {
    val firstCheck = expiry match {
      case Some(actualExpiry) =>
        if (actualExpiry.isBefore(ZonedDateTime.now())) {
          this.copy(expired = true)
        } else {
          this
        }
    }

    if(firstCheck==this){
      maxUses match {
        case Some(maxUsesValue)=>
          if(uses>=maxUsesValue){
            this.copy(expired = true)
          } else {
            this
          }
        case None=>this
      }
    } else firstCheck
  }
}

object ServerTokenEntry extends ((String, ZonedDateTime, Option[ZonedDateTime], Int, Boolean,Option[String])=>ServerTokenEntry) {
  def randomStringFromCharList(length: Int, chars: Seq[Char]): String = {
    val sb = new StringBuilder
    for (i <- 1 to length) {
      val randomNum = util.Random.nextInt(chars.length)
      sb.append(chars(randomNum))
    }
    sb.toString
  }

  def randomAlphaNumericString(length: Int): String = {
    val chars = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
    randomStringFromCharList(length, chars)
  }

  /**
    * the standard way to create a token. Simply specify a duration or leave blank for 60s
    * @param duration how long this token should be valid for
    * @return a ServerTokenEntry (not saved to db)
    */
  def create(associatedId:Option[String]=None, duration:Int=60):ServerTokenEntry = {
    val expiry:ZonedDateTime = ZonedDateTime.now().plusSeconds(duration.toLong)

    ServerTokenEntry(randomAlphaNumericString(36),ZonedDateTime.now(),Some(expiry),0,false, associatedId)
  }
}