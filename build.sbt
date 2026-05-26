name := "amz-authentication"

version := "1.0"

scalaVersion := "3.3.4"

val pekkoVersion     = "1.1.3"
val pekkoHttpVersion = "1.1.0"

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed"     % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream"          % pekkoVersion,
  "org.apache.pekko" %% "pekko-http"            % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
  "io.spray"         %% "spray-json"            % "1.3.6",
  "org.apache.pekko" %% "pekko-http-testkit"    % pekkoHttpVersion % Test,
  "org.scalatest"    %% "scalatest"             % "3.2.19"         % Test
)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:postfixOps"
)

//fork := true

//javaOptions += "-Djavax.net.debug=ssl"
