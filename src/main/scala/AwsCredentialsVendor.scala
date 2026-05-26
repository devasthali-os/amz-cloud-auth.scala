import java.time.Instant
import scala.concurrent.Future

/** Standard AWS STS-shaped temporary credentials, regardless of how they were vended. */
final case class AwsTemporaryCredentials(
    accessKeyId: String,
    secretAccessKey: String,
    sessionToken: String,
    expiration: Instant
)

/** Source of AWS temporary credentials produced by federated identity.
  *
  * Implementations wrap one specific federation flow (Amazon-internal Devasthali,
  * AWS IAM Identity Center / SSO, SAML+STS direct, OIDC role assumption, ...)
  * and expose a uniform "pick a role, get credentials" surface.
  *
  * The abstract `Role` member lets each vendor keep its own typed payload
  * (e.g. `(roleArn, principalArn)` for SAML/Devasthali, `(accountId, roleName)`
  * for SSO) without resorting to `Any`. Callers use path-dependent typing
  * through a `val` reference to the vendor.
  */
trait AwsCredentialsVendor {

  /** Vendor-specific representation of one assumable role/profile. */
  type Role

  /** All roles the current session is allowed to assume. */
  def listRoles(): Future[Seq[Role]]

  /** Human-readable label for picker UIs (CLI menus, role selectors). */
  def labelFor(role: Role): String

  /** Exchange a chosen role for short-lived AWS credentials. */
  def fetchCredentials(role: Role): Future[AwsTemporaryCredentials]

  /** Release any held resources (HTTP clients, actor systems, OS handles). */
  def shutdown(): Unit
}
