import java.io.{FileOutputStream, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import java.util.{Base64, Date}

import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** CLI driver: picks an [[AwsCredentialsVendor]] based on `vendor=` in
  * `application.properties`, fetches AWS temporary credentials, writes them
  * to `~/.aws/credentials` under the configured profile, and re-fetches
  * before expiry.
  *
  * The picker/writer/refresh logic is vendor-agnostic; only `buildVendor`
  * knows the difference between Devasthali and AWS SSO.
  */
object AmzCredentialsCli {

  val config: Config = ConfigFactory.load("application.properties")

  def main(args: Array[String]): Unit = {
    val vendor: AwsCredentialsVendor = buildVendor()

    runPickAndRefreshLoop(vendor)
  }

  private def buildVendor(): AwsCredentialsVendor = {
    val choice = if (config.hasPath("vendor")) config.getString("vendor") else "broker"
    choice.toLowerCase match {
      case "broker" => buildBrokerVendorFromConsole()
      case "sso"    => buildSsoVendorFromAwsConfig()
      case other    => sys.error(s"Unknown vendor=$other (expected 'broker' or 'sso')")
    }
  }

  private def buildBrokerVendorFromConsole(): AwsCredentialsVendor = {
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
    val authBase64 =
      Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
    new AmzTokenClient(authBase64)
  }

  private def buildSsoVendorFromAwsConfig(): AwsCredentialsVendor = {
    val profile = config.getString("aws.sso.profile")
    println(s"[INFO] Using AWS SSO profile [profile $profile] from ~/.aws/config")
    AwsSsoCredentialsVendor.fromAwsConfigProfile(profile)
  }

  private def runPickAndRefreshLoop(vendor: AwsCredentialsVendor): Unit = {
    val roles = Await.result(vendor.listRoles(), 30.seconds)

    if (roles.isEmpty) {
      println("[ERROR] No assumable roles available.")
      vendor.shutdown()
      sys.exit(1)
    }

    val selected: vendor.Role =
      if (roles.size == 1) {
        println(s"[INFO] Single role available, auto-selecting: ${vendor.labelFor(roles.head)}")
        roles.head
      } else {
        println("======================================================================")
        println(s"Please select one of the following role: [0 - ${roles.size - 1}]      ")
        println("======================================================================")
        roles.zipWithIndex.foreach { case (role, index) =>
          println(s"[$index] ${vendor.labelFor(role)}")
        }
        println("======================================================================")

        val roleToToken = scala.io.StdIn.readInt()

        if (roleToToken >= roles.size) {
          println(s"[ERROR] There are only ${roles.size} roles.")
          vendor.shutdown()
          sys.exit(1)
        }
        roles(roleToToken)
      }

    var expiresAt = 0L

    while (true) {
      val currentTime = System.currentTimeMillis()
      if (currentTime >= expiresAt) {
        val expires = fetchAndWriteCredentials(vendor)(selected)
        expiresAt = expires.getTime - (30 * 60 * 1000)
        val renewInSeconds = (expiresAt - currentTime) / 1000
        println(s"[INFO] Relax, I got it. I'll sneakily refresh your token in ${renewInSeconds / 60} minutes ($renewInSeconds seconds) — way before AWS notices. 🕵️")
      }
      Thread.sleep(60000L)
    }
  }

  private def fetchAndWriteCredentials(vendor: AwsCredentialsVendor)(role: vendor.Role): Date = {
    println(s"[INFO] Requesting access token for ${vendor.labelFor(role)}")

    val creds = Await.result(vendor.fetchCredentials(role), 30.seconds)

    val credentialsPath = config.getString("auth.credentials.path")

    try {
      val path = Paths.get(credentialsPath)
      Files.createDirectories(path.getParent)
      Files.createFile(path)
    } catch {
      case _: FileAlreadyExistsException => ()
      case e: Throwable                  => e.printStackTrace()
    }

    println(s"[INFO] Writing to credentials file $credentialsPath")

    try {
      val writer = new PrintWriter(new FileOutputStream(credentialsPath, false))

      writer.write(
        s"""[${config.getString("auth.credentials.name")}]
aws_access_key_id=${creds.accessKeyId}
aws_secret_access_key=${creds.secretAccessKey}
aws_session_token=${creds.sessionToken}
aws_security_token=${creds.sessionToken}""".stripMargin
      )

      val date = Date.from(creds.expiration)
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

}
