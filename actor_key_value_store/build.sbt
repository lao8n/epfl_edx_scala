course := "reactive"
assignment := "kvstore"

testOptions in Test += Tests.Argument(TestFrameworks.JUnit, "-a", "-v", "-s")
parallelExecution in Test := false

val akkaVersion = "2.6.0"

scalaVersion := "2.13.1"
scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-encoding", "UTF-8",
  "-unchecked",
  "-Xlint",
)

libraryDependencies ++= Seq(
  "com.typesafe.akka"        %% "akka-actor"     % akkaVersion,
  "com.typesafe.akka"        %% "akka-testkit"   % akkaVersion % Test,
  "com.novocode"             % "junit-interface" % "0.11"      % Test,
  "com.typesafe.akka"        %% "akka-persistence" % akkaVersion,
  // needed to add this as they do here 
  // https://stackoverflow.com/questions/27511111/akka-persistence-with-play-framework
  // "org.iq80.leveldb" % "leveldb" % "0.7",
  // "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
)
