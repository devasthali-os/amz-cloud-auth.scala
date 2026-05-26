import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.DefaultJsonProtocol._
import spray.json.{JsValue, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Created by prayagupa
  * on 7/14/17.
  */
class AmzTemporaryAuthentication {

  val config: Config                       = ConfigFactory.load("application.properties")
  implicit val system: ActorSystem         = ActorSystem("http-actor")
  implicit val executionContext: ExecutionContext = system.dispatcher

  def resources(authBase64: String): Future[JsValue] = {

    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnectionHttps(config.getString("amz.endpoint"))

    val resourcesFut = Source
      .single(
        HttpRequest(
          uri = Uri(config.getString("amz.resources.roles")),
          headers = List(RawHeader("Authorization", s"Basic $authBase64"))
        )
      )
      .via(connectionFlow)
      .runWith(Sink.head)

    val response = Await.result(resourcesFut, 1000.seconds)

    val actualEntityFuture: Future[String] =
      response.entity.toStrict(10.seconds).map(_.data.decodeString("UTF-8"))

    actualEntityFuture.map(jsonString => jsonString.parseJson)
  }

  def getToken(authenticationBase64: String, resource: String): Future[(String, String, String, String)] = {
    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnectionHttps(config.getString("amz.endpoint"))

    val resourcesFut = Source
      .single(
        HttpRequest(
          method = HttpMethods.POST,
          uri = Uri(config.getString("amz.resources.tokens")),
          headers = List(RawHeader("Authorization", s"Basic $authenticationBase64")),
          entity = HttpEntity(MediaTypes.`application/json`, resource)
        )
      )
      .via(connectionFlow)
      .runWith(Sink.head)

    val httpResponse = Await.result(resourcesFut, 1000.seconds)

    val actualEntityFuture: Future[String] =
      httpResponse.entity.toStrict(10.seconds).map(_.data.decodeString("UTF-8"))

    actualEntityFuture.map(_.parseJson.asJsObject).map { json =>
      (
        json.fields("AccessKey").convertTo[String],
        json.fields("SecretAccessKey").convertTo[String],
        json.fields("Expiration").convertTo[String],
        json.fields("SessionToken").convertTo[String]
      )
    }
  }

}
