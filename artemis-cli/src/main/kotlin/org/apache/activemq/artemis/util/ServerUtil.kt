package org.apache.activemq.artemis.util

import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient
import org.apache.activemq.artemis.jms.client.ActiveMQConnection
import org.apache.activemq.artemis.use
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import javax.jms.Connection

/**
 * A tool to let clients start, stop and kill Artemis servers
 */
object ServerUtil {

    @Throws(Exception::class)
    fun startServer(artemisInstance: String, serverName: String): Process {
        return startServer(artemisInstance, serverName, 0, 0)
    }

    @Throws(Exception::class)
    fun startServer(artemisInstance: String, serverName: String, id: Int, timeout: Int): Process {
        val IS_WINDOWS = System.getProperty("os.name").toLowerCase().trim { it <= ' ' }.startsWith("win")

        val builder = if (IS_WINDOWS) ProcessBuilder("cmd", "/c", "artemis.cmd", "run") else ProcessBuilder("./artemis", "run")

        builder.directory(File(artemisInstance + "/bin"))

        val process = builder.start()
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                process.destroy()
            }
        })

        val outputLogger = ProcessLogger(true, process.inputStream, serverName, false)
        outputLogger.start()

        // Adding a reader to System.err, so the VM won't hang on a System.err.println as identified on this forum thread:
        // http://www.jboss.org/index.html?module=bb&op=viewtopic&t=151815
        val errorLogger = ProcessLogger(true, process.errorStream, serverName, true)
        errorLogger.start()

        // wait for start
        if (timeout != 0) {
            waitForServerToStart(id, timeout)
        }
        return process
    }

    @Throws(InterruptedException::class)
    @JvmStatic
    fun waitForServerToStart(id: Int, timeout: Int) {
        waitForServerToStart("tcp://localhost:" + (61616 + id), timeout.toLong())
    }


    @Throws(InterruptedException::class)
    @JvmStatic
    fun waitForServerToStart(uri: String, timeout: Long) {
        val realTimeout = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < realTimeout) {
            try {
                ActiveMQJMSClient.createConnectionFactory(uri, null).use { cf ->
                    cf.createConnection().close()
                    println("server $uri started")
                }
            } catch (e: Exception) {
                println("awaiting server $uri start at ")
                Thread.sleep(500)
                continue
            }
            break
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun killServer(server: Process?) {
        if (server != null) {
            println("**********************************")
            println("Killing server " + server)
            println("**********************************")
            server.destroy()
            server.waitFor()
            Thread.sleep(1000)
        }
    }

    @JvmStatic
    fun getServer(connection: Connection): Int {
        val session = (connection as ActiveMQConnection).initialSession
        val transportConfiguration = session.sessionFactory.connectorConfiguration
        val port = transportConfiguration.params["port"] as String
        return port.toInt() - 61616
    }

    @JvmStatic
    fun getServerConnection(server: Int, vararg connections: Connection): Connection? {
        for (connection in connections) {
            val session = (connection as ActiveMQConnection).initialSession
            val transportConfiguration = session.sessionFactory.connectorConfiguration
            val port = transportConfiguration.params["port"] as String
            if (Integer.valueOf(port) === server + 61616) {
                return connection
            }
        }
        return null
    }


    class ProcessLogger(val print: Boolean, val inputStream: InputStream, val logName: String, val sendToErr: Boolean) : Thread() {
        init {
            isDaemon = false
        }

        override fun run() {
            try {
                val isr = InputStreamReader(inputStream)
                val br = BufferedReader(isr)
                for (line in br.lines()) {
                    if (print) {
                        if (sendToErr) {
                            System.err.println(logName + "-err:" + line)
                        } else {
                            println(logName + "-out:" + line)
                        }
                    }
                }
            } catch (e: Exception) {
                //
            }
        }
    }
}