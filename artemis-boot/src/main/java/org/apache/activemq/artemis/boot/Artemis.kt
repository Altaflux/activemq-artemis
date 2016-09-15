package org.apache.activemq.artemis.boot

import java.io.File
import java.lang.reflect.InvocationTargetException

import java.net.URLClassLoader
import java.util.*

/**
 * <p>
 * A main class which setups up a classpath and then passes
 * execution off to the ActiveMQ Artemis cli main.
 * </p>
 */
object Artemis {

    @JvmStatic
    fun main(args: Array<String>) {
        val home = System.getProperty("artemis.home")
        val fileHome = if (home != null) File(home) else null
        val instance = System.getProperty("artemis.instance")
        val fileInstance = if (instance != null) File(instance) else null
        execute(fileHome, fileInstance, *args)
    }

    @JvmStatic
    fun execute(fileHome: File?, fileInstance: File?, args: List<String>): Any {
        return execute(fileHome, fileInstance, *args.toTypedArray())
    }

    @JvmStatic
    fun execute(fileHome: File?, fileInstance: File?, vararg args: String): Any {

        val dirs = listOfNotNull(if (fileHome != null) File(fileHome, "lib") else null,
                if (fileInstance != null) File(fileInstance, "lib") else null)

        val urls = listOfNotNull(if (fileHome != null) File(fileHome, "etc").toURI().toURL() else null,
                if (fileInstance != null) File(fileInstance, "etc").toURI().toURL() else null)

                .plus(dirs.filter { it.exists() && it.isDirectory }
                        .map { it.listFiles { file -> it.name.endsWith(".jar") || it.name.endsWith(".zip") }.asList() }
                        .flatMap { it }.sortedWith(Comparator { t, t2 -> t.name.compareTo(t2.name) })
                        .map { it.toURI().toURL() })

        if (fileInstance != null) {
            System.setProperty("java.io.tmpdir", File(fileInstance, "tmp").canonicalPath)
        }
        val loggingConfig = System.getProperty("logging.configuration")
        if (loggingConfig != null) {
            System.setProperty("logging.configuration", fixUpFileURI(loggingConfig))
        }

        val originalCL = Thread.currentThread().contextClassLoader
        val loader = URLClassLoader(urls.toTypedArray())
        Thread.currentThread().contextClassLoader = loader
        val clazz = loader.loadClass("org.apache.activemq.artemis.cli.Artemis")
        val method = clazz.getMethod("execute", File::class.java, File::class.java, args.javaClass)

        try {
            return method.invoke(null, fileHome, fileInstance, args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } finally {
            Thread.currentThread().contextClassLoader = originalCL
        }
    }


    internal fun fixUpFileURI(incValue: String?): String? {
        var value = incValue
        if (value != null && value.startsWith("file:")) {
            value = value.substring("file:".length)
            value = File(value).toURI().toString()
        }
        return value
    }
}