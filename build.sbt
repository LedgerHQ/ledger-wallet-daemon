name := "wallet-daemon"
version := "3.0.0-rc.10"
organization := "co.ledger"
scalaVersion := "2.12.10"
buildInfoPackage := "co.ledger.wallet.daemon"

// Add the branch name to the sbt prompt
enablePlugins(GitBranchPrompt)
enablePlugins(GitVersioning)

lazy val buildInfoKeysInfo = Seq[BuildInfoKey](
  name,
  version,
  scalaVersion)


addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.17")

mainClass in Compile := Some("co.ledger.wallet.daemon.Server")

// For cancelable processes in interactive shell
cancelable in Global := true

// To close the server stream when we run it in interactive shell
fork in run := true
fork in Test := true

// For correct test ordering in integration tests
parallelExecution in Test := true
parallelExecution in IntegrationTest := false

// We don't need to run the tests again when we call assembly
test in assembly := {}

// Use test configuration files
// Use test fixtures and resources in integration tests
unmanagedResourceDirectories in IntegrationTest += baseDirectory.value / "src" / "it" / "resources"

// -a: Show full stack traces
// -q: Hide logs for successful tests
testOptions in Test := Seq(Tests.Argument(TestFrameworks.JUnit, "-a", "-q"))

enablePlugins(JavaAgent, JavaServerAppPackaging)

javaAgents += "com.datadoghq" % "dd-java-agent" % "0.65.0" % "dist"

// Inspired by https://tpolecat.github.io/2017/04/25/scalac-flags.html
scalacOptions ++= Seq(
  "-deprecation",                      // Emit warning and location for usages of deprecated APIs.
  "-encoding", "utf-8",                // Specify character encoding used by source files.
  "-explaintypes",                     // Explain type errors in more detail.
  "-feature",                          // Emit warning and location for usages of features that should be imported explicitly.
  "-language:existentials",            // Existential types (besides wildcard types) can be written and inferred
  "-language:higherKinds",             // Allow higher-kinded types
  "-language:implicitConversions",     // Allow definition of implicit functions called views
  "-unchecked",                        // Enable additional warnings where generated code depends on assumptions.
  "-Xfatal-warnings",                  // Fail the compilation if there are any warnings.
  "-Xlint:constant",                   // Evaluation of a constant arithmetic expression results in an error.
  "-Xlint:delayedinit-select",         // Selecting member of DelayedInit.
  "-Xlint:doc-detached",               // A Scaladoc comment appears to be detached from its element.
  "-Xlint:inaccessible",               // Warn about inaccessible types in method signatures.
  "-Xlint:infer-any",                  // Warn when a type argument is inferred to be `Any`.
  "-Xlint:missing-interpolator",       // A string literal appears to be missing an interpolator id.
  "-Xlint:nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
  "-Xlint:nullary-unit",               // Warn when nullary methods return Unit.
  "-Xlint:option-implicit",            // Option.apply used implicit view.
  "-Xlint:poly-implicit-overload",     // Parameterized overloaded implicit methods are not visible as view bounds.
  "-Xlint:private-shadow",             // A private field (or class parameter) shadows a superclass field.
  "-Xlint:stars-align",                // Pattern sequence wildcard must align with sequence component.
  "-Xlint:type-parameter-shadow",      // A local type parameter shadows a type already in scope.
  "-Xlint:unsound-match",              // Pattern match may not be typesafe.
  "-Ypartial-unification",             // Enable partial unification in type constructor inference
  "-Ywarn-dead-code",                  // Warn when dead code is identified.
  "-Ywarn-extra-implicit",             // Warn when more than one implicit parameter section is defined.
  "-Ywarn-inaccessible",               // Warn about inaccessible types in method signatures.
  "-Ywarn-infer-any",                  // Warn when a type argument is inferred to be `Any`.
  "-Ywarn-nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
  "-Ywarn-nullary-unit",               // Warn when nullary methods return Unit.
  "-Ywarn-unused:implicits",           // Warn if an implicit parameter is unused.
  "-Ywarn-unused:imports",             // Warn if an import selector is not referenced.
  "-Ywarn-unused:locals",              // Warn if a local definition is unused.
  "-Ywarn-unused:params",              // Warn if a value parameter is unused.
  "-Ywarn-unused:patvars",             // Warn if a variable bound in a pattern is unused.
  "-Ywarn-unused:privates"            // Warn if a private member is unused.
)

// scalastyle:off
lazy val versions = new {
  val andrebeat  = "0.4.0"
  val bitcoinj   = "0.14.4"
  val cats       = "1.5.0"
  val circe      = "0.12.3"
  val finatra    = "19.11.0"
  val guice      = "4.0"
  val junit      = "4.12"
  val junitI     = "0.11"
  val h2         = "1.4.192"
  val logback    = "1.1.7"
  val postgres   = "42.2.12"
  val scalacheck = "1.13.4"
  val scalatest  = "3.0.0"
  val slick      = "3.3.2"
  val specs2     = "2.4.17"
  val sqlite     = "3.7.15-M1"
  val tyrus      = "1.13.1"
  val websocket  = "1.1"
  val web3j      = "4.5.1"
  val guava      = "28.1-jre"
  val rabbitmq   = "5.9.0"
  val akka       = "2.6.10"
  val akkaRabbitMQ = "6.0.0"
  val scalaredis = "3.30"
}
// scalastyle:on

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin).settings(buildInfoKeys := buildInfoKeysInfo, buildInfoKeys ++= Seq[BuildInfoKey]("commitHash" -> git.gitHeadCommit.value))
  .configs(IntegrationTest)
  .settings(
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "io.github.andrebeat"          %% "scala-pool"             % versions.andrebeat,
      "org.bitcoinj"                 %  "bitcoinj-core"          % versions.bitcoinj,
      "org.typelevel"                %% "cats-core"              % versions.cats,
      "com.twitter"                  %% "finatra-http"           % versions.finatra,
      "com.twitter"                  %% "finatra-jackson"        % versions.finatra,
      "io.github.finagle"            %% "finagle-postgres"       % "0.12.0",
      "com.h2database"               %  "h2"                     % versions.h2,
      "ch.qos.logback"               %  "logback-classic"        % versions.logback,
      "org.postgresql"               %  "postgresql"             % versions.postgres,
      "com.typesafe.slick"           %% "slick"                  % versions.slick,
      "com.typesafe.slick"           %% "slick-hikaricp"         % versions.slick,
      "org.xerial"                   %  "sqlite-jdbc"            % versions.sqlite,
      "org.glassfish.tyrus.bundles"  % "tyrus-standalone-client" % versions.tyrus,
      "javax.websocket"              % "javax.websocket-api"     % versions.websocket % "provided",
      "org.web3j"                     % "abi"                    % versions.web3j,
      "org.web3j"                     % "core"                   % versions.web3j,
      "com.google.guava"              % "guava"                  % versions.guava,
      "io.circe"                     %% "circe-core"             % versions.circe,
      "io.circe"                     %% "circe-generic"          % versions.circe,
      "io.circe"                     %% "circe-parser"           % versions.circe,
      "com.rabbitmq"                 %  "amqp-client"            % versions.rabbitmq,
      "com.typesafe.akka"            %% "akka-actor"             % versions.akka,
      "com.typesafe.akka"            %% "akka-slf4j"             % versions.akka,
      "com.newmotion"                %% "akka-rabbitmq"          % versions.akkaRabbitMQ,
      "net.debasishg"                %% "redisclient"            % versions.scalaredis,

      // Tests dependencies
      "org.specs2"                   %% "specs2-mock"            % versions.specs2     % "it",
      "com.google.inject.extensions" %  "guice-testlib"          % versions.guice      % "it",
      "com.novocode"                 %  "junit-interface"        % versions.junitI     % "it",
      "org.scalacheck"               %% "scalacheck"             % versions.scalacheck % "it",
      "org.scalatest"                %% "scalatest"              % versions.scalatest  % "it",
      "junit"                        %  "junit"                  % versions.junit      % "it",
      "com.twitter"                  %% "finatra-http"           % versions.finatra    % "it",
      "com.twitter"                  %% "finatra-jackson"        % versions.finatra    % "it",
      "com.twitter"                  %% "inject-server"          % versions.finatra    % "it",
      "com.twitter"                  %% "inject-app"             % versions.finatra    % "it",
      "com.twitter"                  %% "inject-core"            % versions.finatra    % "it",
      "com.twitter"                  %% "inject-modules"         % versions.finatra    % "it",
      "com.twitter"                  %% "finatra-http"           % versions.finatra    % "it" classifier "tests",
      "com.twitter"                  %% "finatra-jackson"        % versions.finatra    % "it" classifier "tests",
      "com.twitter"                  %% "inject-server"          % versions.finatra    % "it" classifier "tests",
      "com.twitter"                  %% "inject-app"             % versions.finatra    % "it" classifier "tests",
      "com.twitter"                  %% "inject-core"            % versions.finatra    % "it" classifier "tests",
      "com.twitter"                  %% "inject-modules"         % versions.finatra    % "it" classifier "tests",
      "com.typesafe.akka"            %% "akka-testkit"           % versions.akka       % "it"
    )
  )

libraryDependencies ++= Seq(
  compilerPlugin("com.github.ghik" %% "silencer-plugin" % "1.3.1"),
  "com.github.ghik" %% "silencer-lib" % "1.3.1" % Provided,
  "com.datadoghq" % "java-dogstatsd-client" % "2.10.1"
)
