package net.thunderklaus

import sbt._
import sbt.Keys._
import java.io.File
import com.github.siasia.WebPlugin._

object GwtPlugin extends Plugin {

  lazy val Gwt = config("gwt") extend (Compile)

  val gwtModules = TaskKey[Seq[String]]("gwt-modules")
  val gwtCompile = TaskKey[Unit]("gwt-compile", "Runs the GWT compiler")
  val gwtDevMode = TaskKey[Unit]("gwt-devmode", "Runs the GWT devmode shell")
  val gwtVersion = SettingKey[String]("gwt-version")
  val gaeSdkPath = SettingKey[Option[String]]("gae-sdk-path")

  var gwtModule: Option[String] = None
  val gwtSetModule = Command.single("gwt-set-module") { (state, arg) =>
    Project.evaluateTask(gwtModules, state) match {
      case Some(Value(mods)) => {
        gwtModule = mods.find(_.toLowerCase.contains(arg.toLowerCase))
        gwtModule match {
          case Some(m) => println("gwt-devmode will run: " + m)
          case None => println("No match for '" + arg + "' in " + mods.mkString(", "))
        }
      }
      case _ => None
    }
    state
  }

  lazy val gwtSettings: Seq[Setting[_]] = webSettings ++ gwtOnlySettings

  lazy val gwtOnlySettings: Seq[Setting[_]] = inConfig(Gwt)(Defaults.configSettings) ++ Seq(
    managedClasspath in Gwt <<= (managedClasspath in Compile, update) map {
      (cp, up) => cp ++ Classpaths.managedJars(Provided, Set("src"), up)
    },
    unmanagedClasspath in Gwt <<= (unmanagedClasspath in Compile).identity,
    gwtVersion := "2.3.0",
    gaeSdkPath := None,
    libraryDependencies <++= gwtVersion(gwtVersion => Seq(
      "com.google.gwt" % "gwt-user" % gwtVersion % "provided",
      "com.google.gwt" % "gwt-dev" % gwtVersion % "provided",
      "javax.validation" % "validation-api" % "1.0.0.GA" % "provided" withSources (),
      "com.google.gwt" % "gwt-servlet" % gwtVersion)),
    gwtModules <<= (javaSource in Compile, resourceDirectory in Compile) map {
      (javaSource, resources) => findGwtModules(javaSource) ++ findGwtModules(resources)
    },

    gwtDevMode <<= (dependencyClasspath in Gwt, javaSource in Compile,
                    gwtModules, gaeSdkPath, temporaryWarPath, streams) map {
      (dependencyClasspath, javaSource, gwtModules, gaeSdkPath, warPath, s) => {
        def gaeFile (path :String*) = gaeSdkPath.map(_ +: path mkString(File.separator))
        val module = gwtModule.getOrElse(gwtModules.head)
        val cp = dependencyClasspath.map(_.data.absolutePath) ++
          gaeFile("lib", "appengine-tools-api.jar").toList :+ javaSource.absolutePath
        val javaArgs = gaeFile("lib", "agent", "appengine-agent.jar") match {
          case None => Nil
          case Some(path) => List("-javaagent:" + path)
        }
        val gwtArgs = gaeSdkPath match {
          case None => Nil
          case Some(path) => List(
            "-server", "com.google.appengine.tools.development.gwt.AppEngineLauncher")
        }
        val command = mkGwtCommand(
          cp, javaArgs, "com.google.gwt.dev.DevMode", warPath, gwtArgs, module)
        s.log.info("Running GWT devmode on: " + module)
        s.log.debug("Running GWT devmode command: " + command)
        command !
      }
    },
    gwtDevMode <<= gwtDevMode.dependsOn(prepareWebapp),

    gwtCompile <<= (dependencyClasspath in Gwt, javaSource in Compile,
                    gwtModules, temporaryWarPath, streams) map {
      (dependencyClasspath, javaSource, gwtModules, warPath, s) => {
        val cp = dependencyClasspath.map(_.data.absolutePath) :+ javaSource.absolutePath
        val command = mkGwtCommand(
          cp, Nil, "com.google.gwt.dev.Compiler", warPath, Nil, gwtModules.mkString(" "))
        s.log.info("Compiling GWT modules: " + gwtModules.mkString(","))
        s.log.debug("Running GWT compiler command: " + command)
        command !
      }
    },
    gwtCompile <<= gwtCompile.dependsOn(prepareWebapp),
    packageWar <<= packageWar.dependsOn(gwtCompile),

    commands ++= Seq(gwtSetModule)
  )

  private def mkGwtCommand(cp: Seq[String], javaArgs: List[String], clazz: String, warPath: File,
                           gwtArgs: List[String], modules: String) =
    (List("java", "-cp", cp.mkString(File.pathSeparator)) ++ javaArgs ++
     List(clazz, "-war", warPath.absolutePath) ++ gwtArgs :+ modules).mkString(" ")

  private def findGwtModules(srcRoot: File): Seq[String] = {
    import Path.relativeTo
    val files = (srcRoot ** "*.gwt.xml").get
    val relativeStrings = files.flatMap(_ x relativeTo(srcRoot)).map(_._2)
    relativeStrings.map(_.dropRight(".gwt.xml".length).replace(File.separator, "."))
  }
}
