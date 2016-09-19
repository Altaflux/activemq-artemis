package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.Artemis
import org.apache.activemq.artemis.cli.commands.tools.LockAbstract
import org.apache.activemq.artemis.components.ExternalComponent
import org.apache.activemq.artemis.core.config.impl.FileConfiguration
import org.apache.activemq.artemis.factory.BrokerFactory
import org.apache.activemq.artemis.factory.SecurityManagerFactory
import org.apache.activemq.artemis.integration.Broker
import org.apache.activemq.artemis.integration.bootstrap.ActiveMQBootstrapLogger
import org.apache.activemq.artemis.utils.ReusableLatch
import java.io.File
import java.util.*


@Command(name = "run", description = "runs the broker instance")
class Run : LockAbstract() {

    @Option(name = arrayOf("--allow-kill"), description = "This will allow the server to kill itself. Useful for tests (failover tests for instance)")
    internal var allowKill: Boolean = false

    private lateinit var server: Broker

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        Artemis.printBanner()

        createDirectories(fileConfiguration)
        val broker = brokerDTO
        addShutdownHook(broker.server.configurationFile.parentFile)
        val security = SecurityManagerFactory.create(broker.security)
        server = BrokerFactory.createServer(broker.server, security)
        server.start()


        if (broker.web != null) {
            broker.components.add(broker.web)
        }
        for (componentDTO in broker.components) {
            val clazz = this.javaClass.classLoader.loadClass(componentDTO.componentClassName)
            val component = clazz.newInstance() as ExternalComponent
            component.start()
            server.server.addExternalComponent(component)
        }
        return null
    }

    private fun createDirectories(fileConfiguration: FileConfiguration) {
        fileConfiguration.pagingLocation.mkdirs()
        fileConfiguration.journalLocation.mkdirs()
        fileConfiguration.bindingsLocation.mkdirs()
        fileConfiguration.largeMessagesLocation.mkdirs()
    }

    private fun addShutdownHook(configurationDir: File) {
        latchRunning.countUp()
        val file = File(configurationDir, "STOP_ME")
        if (file.exists()) {
            if (file.delete()) {
                ActiveMQBootstrapLogger.LOGGER.errorDeletingFile(file.absolutePath)
            }
        }
        val fileKill = File(configurationDir, "KILL_ME")
        if (fileKill.exists()) {
            if (fileKill.delete()) {
                ActiveMQBootstrapLogger.LOGGER.errorDeletingFile(fileKill.absolutePath)
            }
        }
        val timer = Timer("ActiveMQ Artemis Server Shutdown Timer", true)

        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (allowKill && fileKill.exists()) {
                    try {
                        System.err.println("Halting by user request")
                        fileKill.delete()
                    } catch (e: Exception) {
                    }
                    Runtime.getRuntime().halt(0)
                }
                if (file.exists()) {
                    try {
                        server.stop()
                    } finally {
                        println("Server stopped!")
                        System.out.flush()
                        latchRunning.countDown()
                        if (!embedded) {
                            Runtime.getRuntime().exit(0)
                        }
                    }
                }
            }

        }, 500, 500)
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                try {
                    server.stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    companion object {
        @JvmStatic
        var embedded = false
        @JvmStatic
        val latchRunning = ReusableLatch(0)
    }
}