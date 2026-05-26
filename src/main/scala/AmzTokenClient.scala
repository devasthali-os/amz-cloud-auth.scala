import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.DefaultJsonProtocol._
import spray.json._

import java.time.OffsetDateTime
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object AmzTokenClient {

  /** A SAML-federated role assumable through the Amazon-internal Devasthali endpoint.
    *
    * `roleArn`      — the IAM role the user wants to assume.
    * `principalArn` — the SAML identity-provider that vouches for the user.
    */
  final case class Role(roleArn: String, principalArn: String)
}

/** HTTP client for the Amazon-internal `amztoken.devasthali.net` SAML→STS proxy.
  *
  * Takes the user's pre-encoded HTTP-Basic credential at construction (single
  * sign-on for the lifetime of the instance) and exposes the generic
  * [[AwsCredentialsVendor]] surface: list roles, exchange a role for STS-style
  * temporary credentials.
  */
class AmzTokenClient(authBase64: String) extends AwsCredentialsVendor {

  override type Role = AmzTokenClient.Role

  val config: Config                              = ConfigFactory.load("application.properties")
  implicit val system: ActorSystem                = ActorSystem("http-actor")
  implicit val executionContext: ExecutionContext = system.dispatcher

  override def listRoles(): Future[Seq[Role]] = {
    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnectionHttps(config.getString("broker.endpoint"))

    val responseFut = Source
      .single(
        HttpRequest(
          uri = Uri(config.getString("broker.resources.roles")),
          headers = List(RawHeader("Authorization", s"Basic $authBase64"))
        )
      )
      .via(connectionFlow)
      .runWith(Sink.head)

    val response = Await.result(responseFut, 1000.seconds)

    response.entity
      .toStrict(10.seconds)
      .map(_.data.decodeString("UTF-8"))
      .map { body =>
        body.parseJson.asInstanceOf[JsArray].elements.map { el =>
          val obj = el.asJsObject
          AmzTokenClient.Role(
            roleArn = obj.fields("Role").convertTo[String],
            principalArn = obj.fields("Principal").convertTo[String]
          )
        }.toSeq
      }
  }

  override def labelFor(role: Role): String = role.roleArn

  override def fetchCredentials(role: Role): Future[AwsTemporaryCredentials] = {
    val body =
      s"""{
           "Role": "${role.roleArn}",
           "Principal": "${role.principalArn}"
          }""".stripMargin

    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnectionHttps(config.getString("broker.endpoint"))

    val responseFut = Source
      .single(
        HttpRequest(
          method = HttpMethods.POST,
          uri = Uri(config.getString("broker.resources.tokens")),
          headers = List(RawHeader("Authorization", s"Basic $authBase64")),
          entity = HttpEntity(MediaTypes.`application/json`, body)
        )
      )
      .via(connectionFlow)
      .runWith(Sink.head)

    val httpResponse = Await.result(responseFut, 1000.seconds)

    httpResponse.entity
      .toStrict(10.seconds)
      .map(_.data.decodeString("UTF-8"))
      .map { responseBody =>
        val json = responseBody.parseJson.asJsObject
        AwsTemporaryCredentials(
          accessKeyId = json.fields("AccessKey").convertTo[String],
          secretAccessKey = json.fields("SecretAccessKey").convertTo[String],
          sessionToken = json.fields("SessionToken").convertTo[String],
          expiration = OffsetDateTime.parse(json.fields("Expiration").convertTo[String]).toInstant
        )
      }
  }

  override def shutdown(): Unit = {
    val _ = system.terminate()
  }
}
