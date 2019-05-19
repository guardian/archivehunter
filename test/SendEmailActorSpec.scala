import akka.actor.Props
import org.specs2.mutable.Specification
import akka.pattern.ask
import akka.testkit.TestProbe
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{Message, ReceiveMessageRequest, ReceiveMessageResult}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{SESClientManager, SQSClientManager}
import models.{EmailTemplateAssociationDAO, SESMessageFormat, UserProfileDAO}
import org.specs2.mock.Mockito
import play.api.Configuration
import services.GenericSqsActor.{HandleDomainMessage, HandleNextSqsMessage}
import services.SendEmailActor

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class SendEmailActorSpec extends Specification with Mockito{
  "SendEmailActor ! HandleNextSqsMessage" should {
    "correctly convert the message to a domain object and forward" in new AkkaTestkitSpecs2Support {
      val replyObjectString = """{"eventType":"Bounce","bounce":{"bounceType":"Transient","bounceSubType":"General","bouncedRecipients":[{"emailAddress":"user@company.com","action":"failed","status":"5.7.1","diagnosticCode":"smtp; 550-5.7.1 Unauthenticated email from deploydomain.co.uk is not accepted due to\n550-5.7.1 domain's DMARC policy. Please contact the administrator of\n550-5.7.1 deploydomain.co.uk domain if this was a legitimate mail. Please visit\n550-5.7.1  https://support.google.com/mail/answer/2451690 to learn about the\n550 5.7.1 DMARC initiative."}],"timestamp":"2019-05-17T16:40:06.766Z","feedbackId":"someid-000000","reportingMTA":"dsn; a7-23.smtp-out.eu-west-1.amazonses.com"},"mail":{"timestamp":"2019-05-17T16:40:05.939Z","source":"archivehunter@deploydomain.co.uk","sourceArn":"arn:aws:ses:eu-west-1:437862376:identity/deploydomain.co.uk","sendingAccountId":"437862376","messageId":"msgid-000000","destination":["user@company.com"],"headersTruncated":false,"headers":[{"name":"Date","value":"Fri, 17 May 2019 16:40:06 +0000"},{"name":"From","value":"archivehunter@deploydomain.co.uk"},{"name":"Reply-To","value":"multimediatech@theguardian.com"},{"name":"To","value":"andy.gallagher@guardian.co.uk"},{"name":"Message-ID","value":"<1089894309.3876937.1558111206087.JavaMail.ec2-user@ip-10-0-107-255.eu-west-1.compute.internal>"},{"name":"Subject","value":"Test one"},{"name":"MIME-Version","value":"1.0"},{"name":"Content-Type","value":"multipart/alternative;  boundary=\"----=_Part_3876936_1833649642.1558111206086\""},{"name":"Content-Transfer-Encoding","value":"quoted-printable"}],"commonHeaders":{"from":["archivehunter@deploydomain.co.uk"],"replyTo":["admin@deploydomain.co.uk"],"date":"Fri, 17 May 2019 16:40:06 +0000","to":["testuser@company.co.uk"],"messageId":"messageid-000000","subject":"Test one"},"tags":{"ses:operation":["SendTemplatedEmail"],"ses:configuration-set":["SESConfigurationSet-DFsgSHGDH"],"ses:source-ip":["11.22.33.44"],"ses:from-domain":["deploydomain.co.uk"]}}}"""

      val mockConfig = Configuration.from(Map("email.sesNotificationsQueue"->"someQueue"))
      val mockSESClientManager = mock[SESClientManager]
      val mockSQSClientManager = mock[SQSClientManager]
      val mockEmailTemplateAssociationDAO = mock[EmailTemplateAssociationDAO]
      val mockSqsClient = mock[AmazonSQS]
      val mockUserProfileDAO = mock[UserProfileDAO]
      val fakeResponse = new ReceiveMessageResult().withMessages(Seq(new Message().withBody(replyObjectString)).asJava)

      mockSqsClient.receiveMessage(any[ReceiveMessageRequest]) returns fakeResponse
      val ownRefProbe = TestProbe()
      val toTest = system.actorOf(Props(new SendEmailActor(mockConfig, mockSESClientManager, mockSQSClientManager, mockEmailTemplateAssociationDAO, mockUserProfileDAO, system) {
        override val ownRef = ownRefProbe.ref
        override protected val sqsClient = mockSqsClient
      }))

      val mockRequest = new ReceiveMessageRequest()

      toTest ! HandleNextSqsMessage(mockRequest)

      ownRefProbe.expectMsgAnyClassOf(30 seconds, classOf[HandleDomainMessage[SESMessageFormat]])
      ownRefProbe.expectMsgAnyClassOf(30 seconds, classOf[HandleNextSqsMessage])
    }
  }
}
