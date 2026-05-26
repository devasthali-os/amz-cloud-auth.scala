import java.nio.charset.StandardCharsets
import java.util.Base64

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Created by prayagupd
  * on 6/2/17.
  */
class AmzTemporaryAuthenticationSpecs extends AnyFunSuite with Matchers {

  val auth = new AmzTemporaryAuthentication

  val authBase64 = Base64.getEncoder.encodeToString(s"test:test".getBytes(StandardCharsets.UTF_8))

  //FIXME stupid test
  test("connects to Amz Resources") {

    val response: Future[JsValue] = auth.resources(authBase64)

    response.map { r =>
      r.asJsObject.fields.size should be > 0

      println(s"[INFO] ${r.asJsObject}")
    }
  }

  test("tokens") {

    auth.getToken(
      authBase64,
      """{
         "Role":"arn:aws:iam::accountId:role/SomeRole",
        "Principal":"arn:aws:iam::accountId:saml-provider/DWM"
        }""".stripMargin
    )

    Thread.sleep(20000)
  }

}
