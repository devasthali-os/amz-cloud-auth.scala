name := "amz-authentication"

version := "1.0"

scalaVersion := "3.3.4"

val pekkoVersion     = "1.1.3"
val pekkoHttpVersion = "1.1.0"
val awsSdkVersion    = "2.44.12"

libraryDependencies ++= Seq(
  "org.apache.pekko"       %% "pekko-actor-typed"     % pekkoVersion,
  "org.apache.pekko"       %% "pekko-stream"          % pekkoVersion,
  "org.apache.pekko"       %% "pekko-http"            % pekkoHttpVersion,
  "org.apache.pekko"       %% "pekko-http-spray-json" % pekkoHttpVersion,
  "io.spray"               %% "spray-json"            % "1.3.6",
  "software.amazon.awssdk"  % "sso"                   % awsSdkVersion,
  "org.apache.pekko"       %% "pekko-http-testkit"    % pekkoHttpVersion % Test,
  "org.scalatest"          %% "scalatest"             % "3.2.19"         % Test
)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:postfixOps"
)

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.concat
  case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
  case "module-info.class" => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

//fork := true

//javaOptions += "-Djavax.net.debug=ssl"
