import java.nio.charset.StandardCharsets
import java.util.Base64

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AmzTokenClientSpec extends AnyFunSuite with Matchers {

  val authBase64: String =
    Base64.getEncoder.encodeToString("test:test".getBytes(StandardCharsets.UTF_8))

  val tokenClient = new AmzTokenClient(authBase64)

  //relies on a live Devasthali endpoint, no real assertions
  test("connects to Amz Resources") {

    val rolesFut: Future[Seq[tokenClient.Role]] = tokenClient.listRoles()

    rolesFut.map { roles =>
      roles.size should be > 0

      println(s"[INFO] roles=$roles")
    }
  }

  test("tokens") {

    val role = AmzTokenClient.Role(
      roleArn      = "arn:aws:iam::accountId:role/SomeRole",
      principalArn = "arn:aws:iam::accountId:saml-provider/DWM"
    )

    tokenClient.fetchCredentials(role)

    Thread.sleep(20000)
  }

}
