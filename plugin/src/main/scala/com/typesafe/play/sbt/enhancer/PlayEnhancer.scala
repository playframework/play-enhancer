package com.typesafe.play.sbt.enhancer

import sbt._
import java.io.File
import sbt.Keys._
import sbt.inc._
import play.core.enhancers.PropertiesEnhancer
import sbt.plugins.JvmPlugin

object Imports {
  val playEnhancerVersion = settingKey[String]("The version used for the play-enhancer dependency")
  val playEnhancerEnabled = settingKey[Boolean]("Whether the Play enhancer is enabled or not")
  val playEnhancerGenerateAccessors = taskKey[Compiler.CompileResult => Compiler.CompileResult]("Create the function that will generate accessors")
  val playEnhancerRewriteAccessors = taskKey[Compiler.CompileResult => Compiler.CompileResult]("Create the function that will rewrite accessors")
}

object PlayEnhancer extends AutoPlugin {

  override def requires = JvmPlugin
  override def trigger = allRequirements

  val autoImport = Imports

  import Imports._

  override def projectSettings = Seq(
    playEnhancerVersion := readResourceProperty("play.enhancer.version.properties", "play.enhancer.version"),
    playEnhancerEnabled := true,
    libraryDependencies += "com.typesafe.play" % "play-enhancer" % playEnhancerVersion.value
  ) ++ inConfig(Compile)(scopedSettings) ++ inConfig(Test)(scopedSettings)

  private def scopedSettings: Seq[Setting[_]] = Seq(
    sources in playEnhancerGenerateAccessors := unmanagedSources.value.filter(_.getName.endsWith(".java")),
    manipulateBytecode <<= Def.taskDyn {
      val compiled = manipulateBytecode.value
      if (playEnhancerEnabled.value) {
        Def.task {
          playEnhancerRewriteAccessors.value(playEnhancerGenerateAccessors.value(compiled))
        }
      } else {
        Def.task(compiled)
      }
    },
    playEnhancerGenerateAccessors <<= bytecodeEnhance(playEnhancerGenerateAccessors, (PropertiesEnhancer.generateAccessors _).curried),
    playEnhancerRewriteAccessors <<= bytecodeEnhance(playEnhancerRewriteAccessors, (PropertiesEnhancer.rewriteAccess _).curried)
  )

  private def bytecodeEnhance(task: TaskKey[_], generateTask: String => File => Boolean): Def.Initialize[Task[Compiler.CompileResult => Compiler.CompileResult]] = Def.task {
    { result =>
      val analysis = result.analysis

      val deps: Classpath = dependencyClasspath.value
      val classes: File = classDirectory.value

      val classpath = (deps.map(_.data.getAbsolutePath).toArray :+ classes.getAbsolutePath).mkString(java.io.File.pathSeparator)

      val extra = if (crossPaths.value) s"_${scalaBinaryVersion.value}" else ""
      val timestampFile = streams.value.cacheDirectory / s"play_instrumentation$extra"
      val lastEnhanced = if (timestampFile.exists) IO.read(timestampFile).toLong else Long.MinValue

      def getClassesForSources(sources: Seq[File]) = {
        sources.flatMap { source =>
          if (analysis.apis.internal(source).compilation.startTime > lastEnhanced) {
            analysis.relations.products(source)
          } else {
            Nil
          }
        }
      }

      val classesToEnhance = getClassesForSources((sources in task).value)
      val enhancedClasses = classesToEnhance.filter(generateTask(classpath))

      IO.write(timestampFile, System.currentTimeMillis.toString)

      if (enhancedClasses.nonEmpty) {
        /**
         * Updates stamp of product (class file) by preserving the type of a passed stamp.
         * This way any stamp incremental compiler chooses to use to mark class files will
         * be supported.
         */
        def updateStampForClassFile(classFile: File, stamp: Stamp): Stamp = stamp match {
          case _: Exists => Stamp.exists(classFile)
          case _: LastModified => Stamp.lastModified(classFile)
          case _: Hash => Stamp.hash(classFile)
        }
        // Since we may have modified some of the products of the incremental compiler, that is, the compiled template
        // classes and compiled Java sources, we need to update their timestamps in the incremental compiler, otherwise
        // the incremental compiler will see that they've changed since it last compiled them, and recompile them.
        val updatedAnalysis = analysis.copy(stamps = enhancedClasses.foldLeft(analysis.stamps) {
          (stamps, classFile) =>
            val existingStamp = stamps.product(classFile)
            if (existingStamp == Stamp.notPresent) {
              throw new java.io.IOException("Tried to update a stamp for class file that is not recorded as "
                + s"product of incremental compiler: $classFile")
            }
            stamps.markProduct(classFile, updateStampForClassFile(classFile, existingStamp))
        })

        result.copy(analysis = updatedAnalysis, hasModified = true)
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
