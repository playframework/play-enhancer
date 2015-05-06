lazy val root = project
  .in(file("."))
  .disablePlugins(BintrayPlugin)
  .aggregate(enhancer, plugin)
  .settings(common: _*)
  .settings(noPublish: _*)
  .settings(
    name := "play-enhancer-root"
  )

lazy val enhancer = project
  .in(file("enhancer"))
  .disablePlugins(BintrayPlugin)
  .settings(common: _*)
  .settings(publishMavenCentral: _*)
  .settings(
    organization := "com.typesafe.play",
    name := "play-enhancer",
    libraryDependencies += "org.javassist" % "javassist" % "3.18.2-GA",
    autoScalaLibrary := false,
    crossPaths := false
  )

lazy val plugin = project
  .in(file("plugin"))
  .dependsOn(enhancer)
  .settings(common: _*)
  .settings(scriptedSettings: _*)
  .settings(publishBintray: _*)
  .settings(
    name := "sbt-play-enhancer",
    organization := "com.typesafe.sbt",
    sbtPlugin := true,
    scriptedLaunchOpts += ("-Dproject.version=" + version.value),
    scriptedLaunchOpts += "-XX:MaxPermSize=256m",
    resourceGenerators in Compile <+= generateVersionFile
  )

// Shared settings

def common = releaseCommonSettings ++ Seq(
  javacOptions in compile ++= Seq("-source", "1.6", "-target", "1.6"),
  homepage := Some(url("https://github.com/playframework/play-enhancer")),
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  pomExtra :=
    <scm>
      <url>git@github.com:playframework/play-enhancer.git</url>
      <connection>scm:git:git@github.com:playframework/play-enhancer.git</connection>
    </scm>
    <developers>
      <developer>
        <id>jroper</id>
        <name>James Roper</name>
        <url>https://jazzy.id.au</url>
      </developer>
    </developers>
)

def publishMavenCentral = sonatypeSettings ++ Seq(
  SonatypeKeys.profileName := "com.typesafe"
)

def publishBintray = Seq(
  publishTo := {
    if (isSnapshot.value) Some(Opts.resolver.sonatypeSnapshots)
    else publishTo.value
  },
  publishMavenStyle := isSnapshot.value,
  bintrayOrganization := Some("playframework"),
  bintrayRepository := "sbt-plugin-releases",
  bintrayPackage := "sbt-play-enhancer",
  bintrayReleaseOnPublish := false
)

def noPublish = Seq(
  publish := {},
  publishLocal := {},
  PgpKeys.publishSigned := {},
  // publish-signed needs this for some reason...
  publishTo := Some(Resolver.file("Dummy repo", target.value / "dummy-repo"))
)

def generateVersionFile = Def.task {
  val version = (Keys.version in enhancer).value
  val file = (resourceManaged in Compile).value / "play.enhancer.version.properties"
  val content = s"play.enhancer.version=$version"
  IO.write(file, content)
  Seq(file)
}

// Release settings
def releaseCommonSettings: Seq[Setting[_]] = releaseSettings ++ {
  import sbtrelease._
  import ReleaseStateTransformations._
  import ReleaseKeys._
  import sbt.complete.Parser

  def inputTaskStep(key: InputKey[_], input: String) = ReleaseStep(action = { state =>
    val extracted = Project.extract(state)
    val inputTask = extracted.get(Scoped.scopedSetting(key.scope, key.key))
    val task = Parser.parse(input, inputTask.parser(state)) match {
      case Right(t) => t
      case Left(msg) => sys.error(s"Invalid programmatic input:\n$msg")
    }
    GlobalPlugin.evaluate(state, extracted.structure, task, key :: Nil)._1
  })

  def taskStep(key: TaskKey[_]) = ReleaseStep(action = { state =>
    Project.extract(state).runTask(key, state)._1
  })

  Seq(
    publishArtifactsAction := PgpKeys.publishSigned.value,
    tagName := (version in ThisBuild).value,

    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      inputTaskStep(scripted in plugin, ""),
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      taskStep(bintrayRelease in plugin),
      inputTaskStep(SonatypeKeys.sonatypeRelease in enhancer, ""),
      pushChanges
    )
  )
}
