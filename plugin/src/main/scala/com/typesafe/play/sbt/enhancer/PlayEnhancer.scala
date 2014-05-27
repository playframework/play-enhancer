package com.typesafe.play.sbt.enhancer

import sbt._
import java.io.File
import sbt.Keys._
import sbt.inc._
import sbt.compiler.AggressiveCompile
import play.core.enhancers.PropertiesEnhancer
import sbt.plugins.JvmPlugin

object Imports {
  object PlayEnhancerKeys {
    val enhancerVersion = SettingKey[String]("playEnhancerVersion", "The version used for the play-enhancer dependency")
    val generateAccessorsSources = TaskKey[Seq[File]]("playGenerateAccessorsSources", "The list of sources to generate accessors for")
    val rewriteAccessorsSources = TaskKey[Seq[File]]("playRewriteAccessorsSources", "The list of sources to rewrite accessors for")
    val timestampFilename = TaskKey[String]("playEnhancerTimestampFilename", "The filename for the timestamp")
    // This is a function because it can't depend on compile, otherwise that would introduce a circular dependency.
    val generateAccessors = TaskKey[Analysis => Analysis]("playGenerateAccessors", "Create the function that will generate and rewrite accessors")
  }
}

object PlayEnhancer extends AutoPlugin {

  override def requires = JvmPlugin

  val autoImport = Imports

  import Imports.PlayEnhancerKeys._

  override def projectSettings = Seq(
    timestampFilename := {
      // Need to include scala version if cross paths compiling is used
      val extra = if (crossPaths.value) s"_${scalaBinaryVersion.value}" else ""
      s"play_instrumentation${extra}"
    },
    enhancerVersion := readResourceProperty("play.enhancer.version.properties", "play.enhancer.version"),
    libraryDependencies += "com.typesafe.play" % "play-enhancer" % enhancerVersion.value
  ) ++ inConfig(Compile)(scopedSettings) ++ inConfig(Test)(scopedSettings)

  private def scopedSettings: Seq[Setting[_]] = Seq(
    generateAccessorsSources := unmanagedSources.value.filter(_.getName.endsWith(".java")),
    rewriteAccessorsSources := sources.value,

    compile := generateAccessors.value(compile.value),

    generateAccessors := { analysis =>
      val deps: Classpath = dependencyClasspath.value
      val classes: File = classDirectory.value

      val classpath = (deps.map(_.data.getAbsolutePath).toArray :+ classes.getAbsolutePath).mkString(java.io.File.pathSeparator)

      val timestampFile = streams.value.cacheDirectory / timestampFilename.value
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

      val generateAccessorsClasses = getClassesForSources(generateAccessorsSources.value)
      val rewriteAccessorsClasses = getClassesForSources(rewriteAccessorsSources.value)

      val classesWithGeneratedAccessors = generateAccessorsClasses.filter(PropertiesEnhancer.generateAccessors(classpath, _))
      val classesWithAccessorsRewritten = rewriteAccessorsClasses.filter(PropertiesEnhancer.rewriteAccess(classpath, _))

      val enhancedClasses = (classesWithGeneratedAccessors ++ classesWithAccessorsRewritten).distinct

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

        // Need to persist the updated analysis.
        val agg = new AggressiveCompile((compileInputs in compile).value.incSetup.cacheFile)
        // Load the old one. We do this so that we can get a copy of CompileSetup, which is the cache compiler
        // configuration used to determine when everything should be invalidated. We could calculate it ourselves, but
        // that would by a heck of a lot of fragile code due to the vast number of things we would have to depend on.
        // Reading it out of the existing file is good enough.
        val existing: Option[(Analysis, CompileSetup)] = agg.store.get()
        // Since we've just done a compile before this task, this should never return None, so don't worry about what to
        // do when it returns None.
        existing.foreach {
          case (_, compileSetup) => agg.store.set(updatedAnalysis, compileSetup)
        }

        updatedAnalysis
      } else {
        analysis
      }
    }
  )

  private def readResourceProperty(resource: String, property: String): String = {
    val props = new java.util.Properties
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    try { props.load(stream) }
    catch { case e: Exception => }
    finally { if (stream ne null) stream.close }
    props.getProperty(property)
  }

}
