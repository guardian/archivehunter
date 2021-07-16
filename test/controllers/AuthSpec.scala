package controllers

import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import auth.{BearerTokenAuth, LoginResultOK}
import com.google.inject.AbstractModule
import com.nimbusds.jwt.JWTClaimsSet
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import controllers.Auth.OAuthResponse
import helpers.HttpClientFactory
import models.{UserProfile, UserProfileDAO}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import play.api.inject.Module
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModule}
import play.api.test.WithApplication
import play.api.test._
import play.api.test.Helpers._
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.mvc.Cookie

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.Date
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class AuthSpec extends Specification with Mockito {
  sequential

  def fakeClaimBuilder = new JWTClaimsSet.Builder().subject("test-user")

  "Auth.claimIsExpired" should {
    "return true if presented with an expiry time in the past" in {
      val now = Instant.now()
      val testTime = ZonedDateTime.ofInstant(now.minusSeconds(500), ZoneId.systemDefault())
      //val claims = fakeClaimBuilder.expirationTime(Date.from(now.minusSeconds(500))).build();
      Auth.claimIsExpired(testTime) must beTrue
    }

    "return false if presented with an expiry time in the future" in {
      val now = Instant.now()
      val testTime = ZonedDateTime.ofInstant(now.plusSeconds(500), ZoneId.systemDefault())
      //val claims = fakeClaimBuilder.expirationTime(Date.from(now.plusSeconds(500))).build();
      Auth.claimIsExpired(testTime) must beFalse
    }
  }

  private val authTestConfig = Map(
    "oAuth"->Map(
      "clientId"->"archivehunter",
      "resource"->"https://oauth-idp",
      "oAuthUri"->"https://oauth-idp/auth",
      "tokenUrl"->"https://oauth-idp/token",
      "authCookieName"->"authtoken",
      "refreshCookieName"->"refreshtoken",
      "tokenSigningCertPath"->"fakekey.pem",
      "enforceSecure"->true,
      "validAudiences"->List("archivehunter")
    )
  )


  def buildMyApp(maybeHttpOverride:Option[HttpClientFactory],
                 mockUserProfileDAO:UserProfileDAO,
                 maybeMockBearerToken:Option[BearerTokenAuth]=None) = {
    import play.api.inject.bind
    val builder = GuiceApplicationBuilder()
      .overrides(bind[UserProfileDAO].toInstance(mockUserProfileDAO))
      .overrides(bind[DynamoClientManager].toInstance(mock[DynamoClientManager])) //we don't need this so remove it because it tries to set up a connection pool
      .configure(authTestConfig)

    val builderWithHttp = maybeHttpOverride.map(http=>builder.overrides(bind[HttpClientFactory].toInstance(http))).getOrElse(builder)
    val builderWithToken = maybeMockBearerToken.map(tok=>builderWithHttp.overrides(bind[BearerTokenAuth].toInstance(tok))).getOrElse(builder)
    builderWithToken.build()
  }

  "Auth.login" should {
    "return a redirect to the IdP based on provided config" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }

      val mockUserProfileDAO = mock[UserProfileDAO]

      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO)) {
        val resultFuture = route(app, FakeRequest(GET, "/login"))
        resultFuture must beSome

        status(resultFuture.get) mustEqual TEMPORARY_REDIRECT
        headers(resultFuture.get).get("Location") must beSome("https://oauth-idp/auth?state=%2F&redirect_uri=https%3A%2F%2Flocalhost%2FoauthCallback&client_id=archivehunter&resource=https%3A%2F%2Foauth-idp&response_type=code")
      }
    }
  }

  "Auth.oauthCallback" should {
    "return a 500 if the server message indicates an error" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]

      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO)) {
        val result = route(app, FakeRequest(GET, "/oauthCallback?state=%2F&error=Something%20failed"))
        result must beSome

        status(result.get) mustEqual INTERNAL_SERVER_ERROR
        contentAsString(result.get) must contain("Auth provider could not log you in")
        val resultCookies = cookies(result.get)
        resultCookies.get("authtoken") must beNone
        resultCookies.get("refreshtoken") must beNone
        session(result.get).get("userProfile") must beNone

        there was no(mockHttp).singleRequest(any, any, any, any)
        there was no(mockUserProfileDAO).userProfileForEmail(any)
        there was no(mockUserProfileDAO).put(any)
      }
    }

    "return a 500 if there was no access token" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]

      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO)) {
        val result = route(app, FakeRequest(GET, "/oauthCallback?"))
        result must beSome

        status(result.get) mustEqual INTERNAL_SERVER_ERROR
        contentAsString(result.get) must contain("Invalid response")

        val resultCookies = cookies(result.get)
        resultCookies.get("authtoken") must beNone
        resultCookies.get("refreshtoken") must beNone
        session(result.get).get("userProfile") must beNone

        there was no(mockHttp).singleRequest(any, any, any, any)
        there was no(mockUserProfileDAO).userProfileForEmail(any)
        there was no(mockUserProfileDAO).put(any)
      }
    }

    "perform an exchange, get the user profile and return a 200 with cookies set if the token was valid" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]
      val mockBearerToken = mock[BearerTokenAuth]

      val returnedContent = OAuthResponse(Some("access-token"),Some("refresh-token"),None).asJson.noSpaces
      val entity = HttpEntity(returnedContent)
      mockHttp.singleRequest(any,any,any,any) returns Future(HttpResponse(StatusCodes.OK, entity=entity))

      val mockClaims = new JWTClaimsSet.Builder().audience("archivehunter").subject("testuser").expirationTime(Date.from(Instant.now().plusSeconds(300))).build()

      val fakeUserProfile = UserProfile("testuser@org.int",false,Seq(),true,None,None,None,None,None,None)

      mockBearerToken.validateToken(any) returns Right(LoginResultOK(mockClaims))
      mockUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(fakeUserProfile)))
      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO, Some(mockBearerToken))) {
        val result = route(app, FakeRequest(GET, "/oauthCallback?code=some-code-here&state=%2Fpath%2Fyou%2Fwere%2Fat"))
        result must beSome

        status(result.get) mustEqual TEMPORARY_REDIRECT
        header("Location",result.get) must beSome("/path/you/were/at")

        val resultCookies = cookies(result.get)
        //resultCookies.get("authtoken") must beSome(Cookie("authtoken","access-token",Some(28800),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)))
        resultCookies.get("refreshtoken") must beSome(Cookie("refreshtoken","refresh-token",Some(28800),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)))
        //session(result.get).get("userProfile") must beSome(fakeUserProfile.asJson.noSpaces)
        session(result.get).get("username") must beSome("testuser")
        there was one(mockHttp).singleRequest(any, any, any, any)
        there was one(mockUserProfileDAO).userProfileForEmail("testuser")
        there was no(mockUserProfileDAO).put(any)
      }
    }

    "perform an exchange, create a user profile and return a 200 with cookies set if the token was valid and no profile existed" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]
      val mockBearerToken = mock[BearerTokenAuth]

      val returnedContent = OAuthResponse(Some("access-token"),Some("refresh-token"),None).asJson.noSpaces
      val entity = HttpEntity(returnedContent)
      mockHttp.singleRequest(any,any,any,any) returns Future(HttpResponse(StatusCodes.OK, entity=entity))

      val mockClaims = new JWTClaimsSet.Builder().audience("archivehunter").subject("testuser").expirationTime(Date.from(Instant.now().plusSeconds(300))).build()

      mockBearerToken.validateToken(any) returns Right(LoginResultOK(mockClaims))
      mockUserProfileDAO.userProfileForEmail(any) returns Future(None)
      mockUserProfileDAO.put(any) returns Future(None)

      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO, Some(mockBearerToken))) {
        val result = route(app, FakeRequest(GET, "/oauthCallback?code=some-code-here&state=%2Fpath%2Fyou%2Fwere%2Fat"))
        result must beSome

        status(result.get) mustEqual TEMPORARY_REDIRECT
        header("Location",result.get) must beSome("/path/you/were/at")

        val resultCookies = cookies(result.get)
        //resultCookies.get("authtoken") must beSome(Cookie("authtoken","access-token",Some(28800),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)))
        resultCookies.get("refreshtoken") must beSome(Cookie("refreshtoken","refresh-token",Some(28800),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)))
        //session(result.get).get("userProfile") must beSome(fakeUserProfile.asJson.noSpaces)
        session(result.get).get("username") must beSome("testuser")

        there was one(mockHttp).singleRequest(any, any, any, any)
        there was one(mockUserProfileDAO).userProfileForEmail("testuser")
        there was one(mockUserProfileDAO).put(any)
      }
    }

    "return an error if the IdP returns an error" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]
      val mockBearerToken = mock[BearerTokenAuth]

      val returnedContent = OAuthResponse(None,None,Some("I don't like you")).asJson.noSpaces
      val entity = HttpEntity(returnedContent)
      mockHttp.singleRequest(any,any,any,any) returns Future(HttpResponse(StatusCodes.BadRequest, entity=entity))

      val mockClaims = new JWTClaimsSet.Builder()
        .audience("archivehunter")
        .subject("testuser")
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .build()

      val fakeUserProfile = UserProfile("testuser@org.int",false,Seq(),true,None,None,None,None,None,None)

      mockBearerToken.validateToken(any) returns Right(LoginResultOK(mockClaims))
      mockUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(fakeUserProfile)))
      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO, Some(mockBearerToken))) {
        val result = route(app, FakeRequest(GET, "/oauthCallback?code=some-code-here&state=%2Fpath%2Fyou%2Fwere%2Fat"))
        result must beSome

        status(result.get) mustEqual INTERNAL_SERVER_ERROR
        header("Location",result.get) must beNone

        val resultCookies = cookies(result.get)
        resultCookies.get("authtoken") must beNone
        resultCookies.get("refreshtoken") must beNone
        session(result.get).get("userProfile") must beNone

        there was one(mockHttp).singleRequest(any, any, any, any)
        there was no(mockUserProfileDAO).userProfileForEmail(any)
        there was no(mockUserProfileDAO).put(any)
      }
    }
  }

  "Auth.refreshIfRequired" should {
    "send a refresh request to the IdP if access and refresh tokens are present, and the access token is expired" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]
      val mockBearerToken = mock[BearerTokenAuth]

      val returnedContent = OAuthResponse(Some("new-access-token"),Some("new-refresh-token"),None).asJson.noSpaces
      val entity = HttpEntity(returnedContent)
      mockHttp.singleRequest(any,any,any,any) returns Future(HttpResponse(StatusCodes.OK, entity=entity))

      val mockClaims = new JWTClaimsSet.Builder()
        .audience("archivehunter")
        .subject("testuser")
        .expirationTime(Date.from(Instant.now().minusSeconds(300)))
        .build()

      val fakeUserProfile = UserProfile("testuser@org.int",false,Seq(),true,None,None,None,None,None,None)

      mockBearerToken.validateToken(any) returns Right(LoginResultOK(mockClaims))
      mockUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(fakeUserProfile)))
      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO, Some(mockBearerToken))) {
        val result = route(app,
          FakeRequest(POST, "/api/loginRefresh")
            .withCookies(
              Cookie("refreshtoken","refresh-token",Some(3600),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)),
            )
            .withSession(
              "username"->"testuser",
              "claimExpiry"->ZonedDateTime.ofInstant(Instant.now().minusSeconds(500),ZoneId.systemDefault()).format(DateTimeFormatter.ISO_DATE_TIME)
            )
        )
        result must beSome

        contentAsString(result.get) mustEqual("""{"status":"ok","detail":"token refreshed"}""")
        status(result.get) mustEqual OK

        val resultCookies = cookies(result.get)
        resultCookies.get("refreshtoken") must beSome(Cookie("refreshtoken","new-refresh-token",Some(28800),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)))
        session(result.get).get("username") must beSome("testuser")
        session(result.get).get("claimExpiry") must beSome

        there was one(mockHttp).singleRequest(any, any, any, any)
        there was one(mockUserProfileDAO).userProfileForEmail("testuser")
        there was no(mockUserProfileDAO).put(any)
      }
    }

    "not send a refresh request to the IdP if access and refresh tokens are present, and the access token is not expired" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]
      val mockBearerToken = mock[BearerTokenAuth]

      val returnedContent = OAuthResponse(Some("new-access-token"),Some("new-refresh-token"),None).asJson.noSpaces
      val entity = HttpEntity(returnedContent)
      mockHttp.singleRequest(any,any,any,any) returns Future(HttpResponse(StatusCodes.OK, entity=entity))

      val mockClaims = new JWTClaimsSet.Builder()
        .audience("archivehunter")
        .subject("testuser")
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .build()

      val fakeUserProfile = UserProfile("testuser@org.int",isAdmin = false,Seq(),allCollectionsVisible = true,None,None,None,None,None,None)

      mockBearerToken.validateToken(any) returns Right(LoginResultOK(mockClaims))
      mockUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(fakeUserProfile)))
      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO, Some(mockBearerToken))) {
        val result = route(app,
          FakeRequest(POST, "/api/loginRefresh")
            .withCookies(
              Cookie("refreshtoken","refresh-token",Some(3600),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)),
            )
            .withSession(
            "username"->"testuser",
            "claimExpiry"->ZonedDateTime.ofInstant(Instant.now().plusSeconds(500),ZoneId.systemDefault()).format(DateTimeFormatter.ISO_DATE_TIME)
          )
        )
        result must beSome

        contentAsString(result.get) mustEqual("""{"status":"not_needed","detail":"no refresh required"}""")
        status(result.get) mustEqual OK

        val resultCookies = cookies(result.get)
        //if no refresh is required we set no extra cookies
        resultCookies.get("authtoken") must beNone
        resultCookies.get("refreshtoken") must beNone
        session(result.get).get("username") must beNone

        there was no(mockHttp).singleRequest(any, any, any, any)
        there was no(mockUserProfileDAO).userProfileForEmail("testuser")
        there was no(mockUserProfileDAO).put(any)
      }
    }

    "error if claimExpiry is not present in the session" in {
      val mockHttp = mock[HttpExt]
      val mockClientFactory = new HttpClientFactory {
        override def build: HttpExt = mockHttp
      }
      val mockUserProfileDAO = mock[UserProfileDAO]
      val mockBearerToken = mock[BearerTokenAuth]

      val returnedContent = OAuthResponse(Some("new-access-token"),Some("new-refresh-token"),None).asJson.noSpaces
      val entity = HttpEntity(returnedContent)
      mockHttp.singleRequest(any,any,any,any) returns Future(HttpResponse(StatusCodes.OK, entity=entity))

      val mockClaims = new JWTClaimsSet.Builder()
        .audience("archivehunter")
        .subject("testuser")
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .build()

      val fakeUserProfile = UserProfile("testuser@org.int",isAdmin = false,Seq(),allCollectionsVisible = true,None,None,None,None,None,None)

      mockBearerToken.validateToken(any) returns Right(LoginResultOK(mockClaims))
      mockUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(fakeUserProfile)))
      new WithApplication(buildMyApp(Some(mockClientFactory), mockUserProfileDAO, Some(mockBearerToken))) {
        val result = route(app,
          FakeRequest(POST, "/api/loginRefresh")
            .withCookies(
              Cookie("refreshtoken","refresh-token",Some(3600),"/",None,secure = true,httpOnly = true,Some(Cookie.SameSite.Strict)),
            )
        )
        result must beSome

        contentAsString(result.get) mustEqual("""{"status":"session_problem","detail":"claim expiry not present or malformed"}""")
        status(result.get) mustEqual INTERNAL_SERVER_ERROR

        val resultCookies = cookies(result.get)
        //if no refresh is required we set no extra cookies
        resultCookies.get("authtoken") must beNone
        resultCookies.get("refreshtoken") must beNone
        session(result.get).get("username") must beNone

        there was no(mockHttp).singleRequest(any, any, any, any)
        there was no(mockUserProfileDAO).userProfileForEmail("testuser")
        there was no(mockUserProfileDAO).put(any)
      }
    }
  }

  "Auth.logout" should {
    "discard user cookies and reset the session" in new WithApplication() {
      val result = route(app, FakeRequest(GET, "/logout"))

      result must beSome
      status(result.get) mustEqual TEMPORARY_REDIRECT
      session(result.get).get("userProfile") must beNone
      header("Location", result.get) must beSome("/")
    }
  }
}
