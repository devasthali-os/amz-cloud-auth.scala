
[install cert for endpoint](https://github.com/devasthali-os/install-cacert.kotlin#usage) so that stupid java can TLS connect, 

```bash
java -jar build/lib/install-cacert.kotlin.jar [host]:[port]

# was enough on Mac Unix OS
# cp jssecacerts $JAVA_HOME/jre/lib/security 

# or following maybe for windows - https://stackoverflow.com/a/32074827/432903

keytool -exportcert -alias endpoint.com-1 -keystore jssecacerts -storepass changeit -file endpoint.com.cert
keytool -importcert -alias endpoint.com -keystore $JAVA_HOME/jre/lib/security/cacerts -storepass changeit -file endpoint.com.cert

```

create [base64 b2t hash](https://wiki.openssl.org/index.php/Enc#Base64_Encoding) of username:password

```bash
openssl enc -base64 <<< 'your_amz_username:your_amz_password'
opensssl enc -base64 -d <<< whatever_bas64
```

| config                |  value                                |
|-----------------------|---------------------------------------|
| amz.endpoint          | _                                     |
| amz.resources.roles   | /authentication/authRoles             |
| amz.resources.tokens  | /authentication/amzToken              |
| amz.basic.auth        | hashcode of username: password        |
| amz.username          | prayagupd                             |
| amz.password          | _                                     |
| auth.credentials.path | /Users/prayagupd/.aws/credentials     |
| auth.credentials.name | default                               |

```
sbt run
```

```bash
curl -XPOST --header "Content-Type application/json" --header "Authorization: Basic base64_hash" -d '{"Role":"arn:aws:iam::accountId:role/SomeRole","Principal":"arn:aws:iam::accountId:saml-provider/DWM"}' https://pbcld-awstoken.duwamish.net/authentication/awsToken

{
  "SecretAccessKey": "REDACTED",
  "AccessKey":       "REDACTED",
  "Expiration":      "REDACTED",
  "SessionToken":    "REDACTED"
}
```

build an artifact
-----------------

```bash
sbt assembly
```
