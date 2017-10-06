package com.typesafe.play.sbt.enhancer

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import xsbti.compile.CompileResult
import xsbti.compile.analysis.Stamp

import sbt.internal.inc.Hash
import sbt.internal.inc.LastModified

import java.io.File

import play.core.enhancers.PropertiesEnhancer

object Imports {
  val playEnhancerVersion = settingKey[String]("The version used for the play-enhancer dependency")
  val playEnhancerEnabled = settingKey[Boolean]("Whether the Play enhancer is enabled or not")
  val playEnhancerGenerateAccessors = taskKey[CompileResult => CompileResult]("Create the function that will generate accessors")
  val playEnhancerRewriteAccessors = taskKey[CompileResult => CompileResult]("Create the function that will rewrite accessors")
}

object PlayEnhancer extends AutoPlugin {

  override def requires = JvmPlugin
  override def trigger = allRequirements

  val autoImport = Imports

  // This is replacement of old Stamp `Exists` representation
  private final val notPresent = "absent"

  import Imports._

  override def projectSettings = Seq(
    playEnhancerVersion := readResourceProperty("play.enhancer.version.properties", "play.enhancer.version"),
    playEnhancerEnabled := true,
    libraryDependencies += "com.typesafe.play" % "play-enhancer" % playEnhancerVersion.value
  ) ++ inConfig(Compile)(scopedSettings) ++ inConfig(Test)(scopedSettings)

  private def scopedSettings: Seq[Setting[_]] = Seq(
    sources in playEnhancerGenerateAccessors := unmanagedSources.value.filter(_.getName.endsWith(".java")),
    manipulateBytecode := {
      // No need to use a dynTask here, since executing these tasks just return functions that do nothing unless
      // they are actually invoked
      val playEnhancerRewriteAccessorsValue = playEnhancerRewriteAccessors.value
      val playEnhancerGenerateAccessorsValue = playEnhancerGenerateAccessors.value
      val manipulateBytecodeValue = manipulateBytecode.value
      if (playEnhancerEnabled.value) {
        playEnhancerRewriteAccessorsValue(playEnhancerGenerateAccessorsValue(manipulateBytecodeValue))
      } else {
        manipulateBytecodeValue
      }
    },
    playEnhancerGenerateAccessors := bytecodeEnhance(playEnhancerGenerateAccessors, (PropertiesEnhancer.generateAccessors _).curried).value,
    playEnhancerRewriteAccessors := bytecodeEnhance(playEnhancerRewriteAccessors, (PropertiesEnhancer.rewriteAccess _).curried).value
  )

  private def bytecodeEnhance(task: TaskKey[_], generateTask: String => File => Boolean): Def.Initialize[Task[CompileResult => CompileResult]] = Def.task {

    val deps: Classpath = dependencyClasspath.value
    val classes: File = classDirectory.value
    val sourcesInTask = (sources in task).value

    val classpath = (deps.map(_.data.getAbsolutePath).toArray :+ classes.getAbsolutePath).mkString(java.io.File.pathSeparator)
    val extra = if (crossPaths.value) s"_${scalaBinaryVersion.value}" else ""
    val timestampFile = streams.value.cacheDirectory / s"play_instrumentation$extra"
    val lastEnhanced = if (timestampFile.exists) IO.read(timestampFile).toLong else Long.MinValue

    { result =>

      val analysis = result.analysis.asInstanceOf[sbt.internal.inc.Analysis]

      def getClassesForSources(sources: Seq[File]): Seq[File] = {
        sources.flatMap { source =>
          // it won't be usual to have more than one enhanceable class
          // defined per file, but it is still possible. So we need to
          // get all the classes defined for a source.
          val classes = analysis.relations.classNames(source)
          classes.flatMap { c =>
            if (analysis.apis.internal(c).compilationTimestamp() > lastEnhanced) {
              analysis.relations.products(source)
            } else {
              Nil
            }
          }
        }.distinct
      }

      val classesToEnhance = getClassesForSources(sourcesInTask)
      val enhancedClasses = classesToEnhance.filter(generateTask(classpath))

      IO.write(timestampFile, System.currentTimeMillis.toString)

      if (enhancedClasses.nonEmpty) {
        /**
         * Updates stamp of product (class file) by preserving the type of a passed stamp.
         * This way any stamp incremental compiler chooses to use to mark class files will
         * be supported.
         */
        def updateStampForClassFile(classFile: File, stamp: Stamp): Stamp = stamp match {
          //case _: Exists => sbt.internal.inc.Stamp.exists(classFile)
          case _: LastModified => sbt.internal.inc.Stamper.forLastModified(classFile)
          case _: Hash => sbt.internal.inc.Stamper.forHash(classFile)
        }
        // Since we may have modified some of the products of the incremental compiler, that is, the compiled template
        // classes and compiled Java sources, we need to update their timestamps in the incremental compiler, otherwise
        // the incremental compiler will see that they've changed since it last compiled them, and recompile them.
        val updatedAnalysis = analysis.copy(stamps = enhancedClasses.foldLeft(analysis.stamps) {
          (stamps, classFile) =>
            val existingStamp = stamps.product(classFile)
            if (existingStamp.writeStamp == notPresent) {
              throw new java.io.IOException("Tried to update a stamp for class file that is not recorded as "
                + s"product of incremental compiler: $classFile")
            }
            stamps.markProduct(classFile, updateStampForClassFile(classFile, existingStamp))
        })

        result.withAnalysis(updatedAnalysis).withHasModified(true)
      } else {
        result
      }

    }
  }

  private def readResourceProperty(resource: String, property: String): String = {
    val props = new java.util.Properties
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    try { props.load(stream) }
    catch { case e: Exception => }
    finally { if (stream ne null) stream.close }
    props.getProperty(property)
  }

}
