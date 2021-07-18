package services.datamigration.streamcomponents

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import org.specs2.mutable.Specification
import services.datamigration.streamcomponents.ConvenientTypes.GenericDynamoEntry

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

class LightboxUpdateBuilderSpec extends Specification {
  implicit val actorSystem = ActorSystem("LightboxUpdateBuilderSpec")
  implicit val mat:Materializer = Materializer.matFromSystem

  "LightboxUpdateBuilder" should {
    "emit update requests for incoming data" in {
      val fakeItem:GenericDynamoEntry = Map(
        "pk"->new AttributeValue().withS("somevalue"),
        "anotherkey"->new AttributeValue().withN("1234"),
        "finalkey"->new AttributeValue().withBOOL(false)
      )

      val resultFuture = Source
        .single(fakeItem)
        .via(LightboxUpdateBuilder("pk",(_)=>Some(new AttributeValue().withS("anothervalue"))))
        .toMat(Sink.seq)(Keep.right)
        .run()

      val result = Await.result(resultFuture, 3.seconds)
      result.length mustEqual 1
      result.head.itemToDelete mustEqual Map("pk"->new AttributeValue().withS("somevalue"))
      result.head.itemToWrite mustEqual Map(
        "pk"->new AttributeValue().withS("anothervalue"),
        "anotherkey"->new AttributeValue().withN("1234"),
        "finalkey"->new AttributeValue().withBOOL(false)
      )
    }

    "error if the requested pk field does not exist " in {
      val fakeItem:GenericDynamoEntry = Map(
        "pk"->new AttributeValue().withS("somevalue"),
        "anotherkey"->new AttributeValue().withN("1234"),
        "finalkey"->new AttributeValue().withBOOL(false)
      )

      val resultFuture = Source
        .single(fakeItem)
        .via(LightboxUpdateBuilder("otherfield",(_)=>Some(new AttributeValue().withS("anothervalue"))))
        .toMat(Sink.seq)(Keep.right)
        .run()

      val result = Try { Await.result(resultFuture, 3.seconds) }
      result must beAFailedTry
      result.failed.get.getMessage.contains("Encountered record of incorrect type") must beTrue
    }
  }

  "not emit anything if there is no change to be made" in {
    val fakeItem:GenericDynamoEntry = Map(
      "pk"->new AttributeValue().withS("somevalue"),
      "anotherkey"->new AttributeValue().withN("1234"),
      "finalkey"->new AttributeValue().withBOOL(false)
    )

    val resultFuture = Source
      .single(fakeItem)
      .via(LightboxUpdateBuilder("pk",(_)=>Some(new AttributeValue().withS("somevalue"))))
      .toMat(Sink.seq)(Keep.right)
      .run()

    val result = Await.result(resultFuture, 3.seconds)
    result.length mustEqual 0
  }
}
