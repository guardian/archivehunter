import java.time.ZonedDateTime

import models.ServerTokenEntry
import org.specs2.mutable.Specification

class ServerTokenEntrySpec extends Specification {
  "ServerTokenEntry.updateCheckExpired" should {
    "return the original object if the token is not expired" in {
      val tkn = ServerTokenEntry.create(forUser=Some("user@test.org"))
      val result = tkn.updateCheckExpired()
      result mustEqual tkn
    }

    "update the expiry flag if the expiry time is passed" in {
      val tkn = ServerTokenEntry.create(forUser=Some("user@test.org")).copy(expiry = Some(ZonedDateTime.now().minusHours(1L)))
      val result = tkn.updateCheckExpired()
      result mustNotEqual tkn
      result.expired must beTrue
    }

    "update the expiry flag if the token has been used too many times" in {
      val tkn = ServerTokenEntry.create(forUser=Some("user@test.org")).copy(uses = 5)
      val result = tkn.updateCheckExpired(maxUses = Some(5))
      result mustNotEqual tkn
      result.expired must beTrue
    }
  }
}
