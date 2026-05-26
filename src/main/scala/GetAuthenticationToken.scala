import java.io.{FileOutputStream, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import java.time.OffsetDateTime
import java.util.{Base64, Date}

import com.typesafe.config.ConfigFactory
import spray.json.JsArray
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Created by prayagupa
  * on 7/17/17.
  */
object GetAuthenticationToken {

  val auth   = new AmzTemporaryAuthentication
  val config = ConfigFactory.load("application.properties")

  def main(args: Array[String]): Unit = {

    System.setProperty("https.protocols", "TLSv1.2")

    if (config.hasPath("auth.server.certs.store") && config.getString("auth.server.certs.store").nonEmpty) {
      System.setProperty("javax.net.ssl.trustStore", config.getString("auth.server.certs.store"))
    }

    print("Please enter your amz username: ")
    val username = scala.io.StdIn.readLine()
    print("Please enter amz password: ")
    val password = Option(System.console()) match {
      case Some(console) => new String(console.readPassword())
      case None          => scala.io.StdIn.readLine()
    }

    val authBase64 = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))

    auth.resources(authBase64).map { res =>
      val roles = res.asInstanceOf[JsArray].elements.zipWithIndex.map { case (role, index) =>
        index -> (role.asJsObject.fields("Role").convertTo[String] -> role.asJsObject.fields("Principal").convertTo[String])
      }

      println("======================================================================")
      println(s"Please select one of the following role: [0 - ${roles.size - 1}]      ")
      println("======================================================================")
      roles.foreach(tuple => println(s"[${tuple._1}] ${tuple._2._1}"))
      println("======================================================================")

      val roleToToken = scala.io.StdIn.readInt()

      if (roleToToken > roles.size) {
        println(s"[ERROR] There are only ${roles.size} roles.")
        auth.system.terminate()
      }

      val selected = roles(roleToToken)

      var expiresAt = 0L

      while (true) {
        val currentTime = System.currentTimeMillis()
        if (currentTime >= expiresAt) {
          val expires = getAuthAccessToken(authBase64, selected)
          expiresAt = expires.getTime - (30 * 60 * 1000)
          println(s"[INFO] But don't worry I will renew the access token in ${(expiresAt - currentTime) / 1000} seconds.")
        }
      }
    }
  }

  def getAuthAccessToken(authenticationBase64: String, selected: (Int, (String, String))): Date = {

    val tokenRequest =
      s"""{
           "Role": "${selected._2._1}",
           "Principal": "${selected._2._2}"
          }""".stripMargin

    println("[INFO] Requesting access token for " + tokenRequest.parseJson)

    val tokenResponse = auth.getToken(authenticationBase64, tokenRequest)

    val expires =
      tokenResponse.map { response =>
        val credentialsPath = config.getString("auth.credentials.path")

        try {
          val path = Paths.get(credentialsPath)
          Files.createFile(path)
        } catch {
          case _: FileAlreadyExistsException => println("")
          case e: Throwable                  => e.printStackTrace()
        }

        println(s"[INFO] Writing to credentials file $credentialsPath")

        try {
          val writer = new PrintWriter(new FileOutputStream(credentialsPath, false))

          writer.write(
            s"""[${config.getString("auth.credentials.name")}]
aws_access_key_id=${response._1}
aws_secret_access_key=${response._2}
aws_session_token=${response._4}
aws_security_token=${response._4}""".stripMargin
          )

          val date = Date.from(OffsetDateTime.parse(response._3).toInstant)
          println(s"[INFO] Updated public credentials to path $credentialsPath, supposed to expire at $date")
          println(
            """
  .--.  .-"     "-.  .--.
 / .. \/  .-. .-.  \/ .. \
 |  '|  /   Y   \  |'  | |
 \   \  \ 0 | 0 /  /   / |
 \ '- ,\.-"`` ``"-./, -' /
  `'-' /_   ^ ^   _\ '-'`
  \._   _./  |
      \   \ `~` /   /
       '._ '-=-' _.'
          '~-||-~'""".stripMargin
          )
          writer.close()

          date
        } catch {
          case e: Throwable =>
            println(s"[ERROR] ${e.getMessage}")
            e.printStackTrace()
            new Date()
        }
      }

    Await.result(expires, 1.seconds)
  }

}
