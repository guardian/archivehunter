package controllers

import com.nimbusds.jwt.JWTClaimsSet
import org.specs2.mutable.Specification

import java.time.Instant
import java.util.Date


class AuthSpec extends Specification{
  "Auth.claimIsExpired" should {
    "return true if presented with an expiry time in the past" in {
      val now = Instant.now()

      val claimsBuilder = new JWTClaimsSet.Builder()
      claimsBuilder.expirationTime(Date.from(now.minusSeconds(500)));

      Auth.claimIsExpired(claimsBuilder.build()) must beTrue
    }

    "return false if presented with an expiry time in the future" in {
      val now = Instant.now()

      val claimsBuilder = new JWTClaimsSet.Builder()
      claimsBuilder.expirationTime(Date.from(now.plusSeconds(500)));

      Auth.claimIsExpired(claimsBuilder.build()) must beFalse
    }
  }
}
