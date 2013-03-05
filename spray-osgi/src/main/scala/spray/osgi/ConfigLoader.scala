package spray.osgi

import scala.Option.option2Iterable
import scala.annotation.tailrec

import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkUtil

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax

object ConfigLoader {
  private def getBundle(clazz: Class[_]): Bundle = {
    FrameworkUtil.getBundle(clazz)
  }

  private def findBundle(className: String): Option[Bundle] = {
    try {
      val clazz = getClass().getClassLoader().loadClass(className)
      Some(getBundle(clazz))
    } catch {
      case _: ClassNotFoundException => None
    }
  }

  /**
   * Load all the config objects. The normal code to do this doesn't seem to
   * work all that well in an OSGI environment.
   */
  def loadSprayConfig(caller: AnyRef): Config = {
    // These classes are used to find the ClassLoaders for the corresponding
    // Spray modules.
    // TODO: This list is incomplete
    val referenceConfigClasses = List(
      "spray.util.UtilSettings",
      "spray.can.HttpCommand",
      "spray.io.BufferBuilder")

    val referenceConfigBundles = for {
      clazz <- referenceConfigClasses
      bundle <- findBundle(clazz)
    } yield {
      (bundle, "/reference.conf")
    }

    val parseOptions = ConfigParseOptions.defaults()
      .setAllowMissing(false)
      .setSyntax(ConfigSyntax.CONF)

    @tailrec def parseAndResolve(
      loaders: List[(Bundle, String)],
      config: Config = ConfigFactory.empty): Config = loaders match {
      case List() =>
        config.resolve()
      case (bundle, configname) :: rest =>
        val c = ConfigFactory.parseURL(bundle.getEntry(configname), parseOptions)
        parseAndResolve(rest, config.withFallback(c))
    }

    parseAndResolve(
      (getBundle(caller.getClass()), "/application.conf") ::
        referenceConfigBundles)
  }
}
