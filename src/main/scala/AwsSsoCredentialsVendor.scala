import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sso.SsoClient
import software.amazon.awssdk.services.sso.model.GetRoleCredentialsRequest
import spray.json.DefaultJsonProtocol._
import spray.json._

import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object AwsSsoCredentialsVendor {

  /** A single AWS-SSO-assumable role identified by AWS account + role name.
    *
    * `profileName` carries the matching `~/.aws/config` `[profile X]` section
    * name purely so the CLI can label the picker output (and so the file
    * writer can use it as the `[X]` section name in `~/.aws/credentials`).
    */
  final case class Role(profileName: String, accountId: String, roleName: String)

  /** Parsed `aws sso login` cache entry. */
  private final case class SsoCachedToken(accessToken: String, expiresAt: Instant)

  /** Build a vendor from a named profile in `~/.aws/config`. Reads:
    *   sso_start_url, sso_region, sso_account_id, sso_role_name
    * and locates the matching access token at `~/.aws/sso/cache/<sha1(start_url)>.json`.
    */
  def fromAwsConfigProfile(profileName: String)(implicit ec: ExecutionContext): AwsSsoCredentialsVendor = {
    val profile = readAwsConfigProfile(profileName)
    def required(key: String): String =
      profile.getOrElse(
        key,
        sys.error(s"Profile [profile $profileName] in ~/.aws/config is missing required key '$key'")
      )
    new AwsSsoCredentialsVendor(
      profileName = profileName,
      startUrl    = required("sso_start_url"),
      ssoRegion   = required("sso_region"),
      accountId   = required("sso_account_id"),
      roleName    = required("sso_role_name")
    )
  }

  /** Hand-parsed `~/.aws/config` reader.
    *
    * Supports the legacy SSO profile format the user has:
    *   [profile NAME]
    *   sso_account_id  = ...
    *   sso_start_url   = ...
    *   sso_region      = ...
    *   sso_role_name   = ...
    *
    * Comments (`#` / `;`) and blank lines are ignored; section headers are
    * matched by exact equality so siblings like `[profile other]` or the
    * newer `[sso-session foo]` block are ignored.
    */
  private[this] def readAwsConfigProfile(profileName: String): Map[String, String] = {
    val configPath = Paths.get(System.getProperty("user.home"), ".aws", "config")
    if (!Files.exists(configPath)) {
      sys.error(s"$configPath not found - run `aws configure sso` or set up the profile manually first.")
    }

    val target = s"[profile $profileName]"
    val lines  = Files.readAllLines(configPath).asScala

    val (_, collected) = lines.foldLeft((false, Map.empty[String, String])) {
      case ((inSection, acc), rawLine) =>
        val line = rawLine.trim
        if (line.isEmpty || line.startsWith("#") || line.startsWith(";")) {
          (inSection, acc)
        } else if (line.startsWith("[") && line.endsWith("]")) {
          (line == target, acc)
        } else if (inSection && line.contains("=")) {
          val parts = line.split("=", 2)
          (inSection, acc + (parts(0).trim -> parts(1).trim))
        } else (inSection, acc)
    }

    if (collected.isEmpty) {
      sys.error(s"Profile [profile $profileName] not found in $configPath")
    }
    collected
  }

  private[this] def readCachedAccessToken(startUrl: String): SsoCachedToken = {
    val cacheDir  = Paths.get(System.getProperty("user.home"), ".aws", "sso", "cache")
    val cacheFile = cacheDir.resolve(sha1Hex(startUrl) + ".json")

    if (!Files.exists(cacheFile)) {
      sys.error(
        s"No cached SSO token at $cacheFile. " +
          s"Run `aws sso login --sso-session ...` or `aws sso login --profile <name>` first."
      )
    }

    val json = new String(Files.readAllBytes(cacheFile), "UTF-8").parseJson.asJsObject
    val token = SsoCachedToken(
      accessToken = json.fields("accessToken").convertTo[String],
      expiresAt   = Instant.parse(json.fields("expiresAt").convertTo[String])
    )

    if (token.expiresAt.isBefore(Instant.now())) {
      sys.error(
        s"Cached SSO token at $cacheFile expired at ${token.expiresAt}. " +
          s"Re-run `aws sso login` to refresh it."
      )
    }
    token
  }

  /** SHA-1 hex digest of the start-URL — matches what the AWS CLI uses to
    * name the cache file when the legacy profile format (with `sso_start_url`
    * directly on the profile, no `sso-session` block) is in use.
    */
  private[this] def sha1Hex(s: String): String = {
    val md     = MessageDigest.getInstance("SHA-1")
    val digest = md.digest(s.getBytes("UTF-8"))
    digest.map(b => f"${b & 0xff}%02x").mkString
  }

  // Expose for the instance method below.
  private[AwsSsoCredentialsVendor] def loadCache(startUrl: String): SsoCachedToken =
    readCachedAccessToken(startUrl)
}

/** Vendor backed by AWS IAM Identity Center (formerly AWS SSO).
  *
  * Does NOT re-implement the OIDC device flow — assumes the user has already
  * run `aws sso login`, which writes the access token to
  * `~/.aws/sso/cache/<sha1(start_url)>.json`. This class reads that cache
  * and uses the token to call `sso:GetRoleCredentials` for the configured
  * `accountId` / `roleName`.
  */
class AwsSsoCredentialsVendor(
    val profileName: String,
    startUrl: String,
    ssoRegion: String,
    accountId: String,
    roleName: String
)(implicit ec: ExecutionContext)
    extends AwsCredentialsVendor {

  override type Role = AwsSsoCredentialsVendor.Role

  private val cached = AwsSsoCredentialsVendor.loadCache(startUrl)

  private val ssoClient =
    SsoClient.builder().region(Region.of(ssoRegion)).build()

  override def listRoles(): Future[Seq[Role]] =
    Future.successful(Seq(AwsSsoCredentialsVendor.Role(profileName, accountId, roleName)))

  override def labelFor(role: Role): String =
    s"${role.profileName}: ${role.accountId} / ${role.roleName}"

  override def fetchCredentials(role: Role): Future[AwsTemporaryCredentials] = Future {
    val response = ssoClient.getRoleCredentials(
      GetRoleCredentialsRequest
        .builder()
        .accessToken(cached.accessToken)
        .accountId(role.accountId)
        .roleName(role.roleName)
        .build()
    )
    val r = response.roleCredentials
    AwsTemporaryCredentials(
      accessKeyId     = r.accessKeyId,
      secretAccessKey = r.secretAccessKey,
      sessionToken    = r.sessionToken,
      expiration      = Instant.ofEpochMilli(r.expiration)
    )
  }

  override def shutdown(): Unit = ssoClient.close()
}
