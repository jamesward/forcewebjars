name := "forcewebjars"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.5"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  ws,
  filters,

  "org.webjars"          %% "webjars-play"                  % "2.3.0-2",
  "org.webjars"          %  "requirejs"                     % "2.1.15",
  "org.webjars"          %  "require-css"                   % "0.1.8-1",
  "org.webjars"          %  "angularjs"                     % "1.2.27",
  "org.webjars"          %  "angular-ui-bootstrap"          % "0.12.0",
  "org.webjars"          %  "bootstrap"                     % "3.3.2",

  "org.scalatestplus"    %% "play"                          % "1.2.0"     % "test"
)

pipelineStages := Seq(digest, gzip)
