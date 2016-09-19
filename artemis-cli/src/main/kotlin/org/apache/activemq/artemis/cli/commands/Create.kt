package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Arguments
import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.CLIException
import org.apache.activemq.artemis.cli.commands.util.SyncCalculation
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType
import org.apache.activemq.artemis.jlibaio.LibaioContext
import org.apache.activemq.artemis.jlibaio.SubmitInfo
import org.apache.activemq.artemis.utils.FileUtil
import java.io.*
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.util.regex.Matcher
import java.util.regex.Pattern

@Command(name = "create", description = "creates a new broker instance")
class Create : InputAbstract() {

    @Arguments(description = "The instance directory to hold the broker's configuration and data.  Path must be writable.", required = true)
    lateinit var directory: File

    @Option(name = arrayOf("--host"), description = "The host name of the broker (Default: 0.0.0.0 or input if clustered)")
    internal var host: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = "0.0.0.0"
            }
            return field
        }

    @Option(name = arrayOf("--default-port"), description = "The port number to use for the main 'artemis' acceptor (Default: 61616)")
    internal var defaultPort = DEFAULT_PORT

    @Option(name = arrayOf("--http-port"), description = "The port number to use for embedded web server (Default: 8161)")
    internal var httpPort = HTTP_PORT

    @Option(name = arrayOf("--ssl-key"), description = "The key store path for embedded web server")
    internal var sslKey: String? = null

    @Option(name = arrayOf("--ssl-key-password"), description = "The key store password")
    internal var sslKeyPassword: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = inputPassword("--ssl-key-password", "Please enter the keystore password:", "password")
            }
            return field
        }
    @Option(name = arrayOf("--use-client-auth"), description = "If the embedded server requires client authentication")
    internal var useClientAuth: Boolean = false

    @Option(name = arrayOf("--ssl-trust"), description = "The trust store path in case of client authentication")
    internal var sslTrust: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = input("--ssl-trust", "Please enter the trust store path:", "/etc/truststore.jks")
            }
            return field
        }

    @Option(name = arrayOf("--ssl-trust-password"), description = "The trust store password")
    internal var sslTrustPassword: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = inputPassword("--ssl-key-password", "Please enter the keystore password:", "password")
            }
            return field
        }

    @Option(name = arrayOf("--name"), description = "The name of the broker (Default: same as host)")
    internal var name: String? = null

    @Option(name = arrayOf("--port-offset"), description = "Off sets the ports of every acceptor")
    internal var portOffset: Int = 0

    @Option(name = arrayOf("--force"), description = "Overwrite configuration at destination directory")
    internal var force: Boolean = false

    @Option(name = arrayOf("--home"), description = "Directory where ActiveMQ Artemis is installed")
    internal var home: File? = null
        get() {
            field = field ?: File(brokerHome)
            return field
        }

    @Option(name = arrayOf("--data"), description = "Directory where ActiveMQ Data is used. Paths are relative to artemis.instance")
    internal var data = "./data"

    @Option(name = arrayOf("--clustered"), description = "Enable clustering")
    internal var clustered = false

    @Option(name = arrayOf("--max-hops"), description = "Number of hops on the cluster configuration")
    internal var maxHops = 0

    @Option(name = arrayOf("--message-load-balancing"), description = "Load balancing policy on cluster. [ON_DEMAND (default) | STRICT | OFF]")
    internal var messageLoadBalancing = MessageLoadBalancingType.ON_DEMAND

    @Option(name = arrayOf("--replicated"), description = "Enable broker replication")
    internal var replicated = false

    @Option(name = arrayOf("--shared-store"), description = "Enable broker shared store")
    internal var sharedStore = false

    @Option(name = arrayOf("--slave"), description = "Valid for shared store or replication: this is a slave server?")
    internal var slave: Boolean = false

    @Option(name = arrayOf("--failover-on-shutdown"), description = "Valid for shared store: will shutdown trigger a failover? (Default: false)")
    internal var failoverOnShutodwn: Boolean = false

    @Option(name = arrayOf("--cluster-user"), description = "The cluster user to use for clustering. (Default: input)")
    internal var clusterUser: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = input("--cluster-user", "Please provide the username:", "cluster-admin")
            }
            return field
        }

    @Option(name = arrayOf("--cluster-password"), description = "The cluster password to use for clustering. (Default: input)")
    internal var clusterPassword: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = inputPassword("--cluster-password", "Please enter the password:", "password-admin")
            }
            return field
        }

    @Option(name = arrayOf("--encoding"), description = "The encoding that text files should use")
    internal var encoding = "UTF-8"

    @Option(name = arrayOf("--java-options"), description = "Extra java options to be passed to the profile")
    internal var javaOptions: String = ""

    @Option(name = arrayOf("--allow-anonymous"), description = "Enables anonymous configuration on security, opposite of --require-login (Default: input)")
    internal var allowAnonymous: Boolean? = null
        get() {
            if (field == null) {
                val value = input("--allow-anonymous | --require-login", "Allow anonymous access? (Y/N):", "Y")
                field = value.toLowerCase() == "y"
            }
            return field
        }

    @Option(name = arrayOf("--require-login"), description = "This will configure security to require user / password, opposite of --allow-anonymous")
    internal var requireLogin: Boolean? = null
        get() {
            field = field ?: !allowAnonymous!!
            return field
        }

    @Option(name = arrayOf("--no-autotune"), description = "Disable auto tuning on the journal.")
    internal var noAutoTune: Boolean = false

    @Option(name = arrayOf("--user"), description = "The username (Default: input)")
    internal var user: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = input("--user", "Please provide the username:", "admin")
            }
            return field
        }

    @Option(name = arrayOf("--password"), description = "The user's password (Default: input)")
    internal var password: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = inputPassword("--password", "Please provide the default password:", "admin")
            }
            return field
        }

    @Option(name = arrayOf("--role"), description = "The name for the role created (Default: input)")
    internal var role: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = input("--role", "Please provide the default role:", "amq")
            }
            return field
        }

    @Option(name = arrayOf("--no-web"), description = "This will remove the web server definition from bootstrap.xml")
    internal var noWeb: Boolean = false

    @Option(name = arrayOf("--queues"), description = "comma separated list of jms queues.")
    internal var queues: String? = null

    @Option(name = arrayOf("--topics"), description = "comma separated list of jms topics ")
    internal var topics: String? = null

    @Option(name = arrayOf("--aio"), description = "Force aio journal on the configuration regardless of the library being available or not.")
    internal var forceLibaio: Boolean = false

    @Option(name = arrayOf("--nio"), description = "Force nio journal on the configuration regardless of the library being available or not.")
    internal var forceNIO: Boolean = false

    @Option(name = arrayOf("--disable-persistence"), description = "Disable message persistence to the journal")
    internal var disablePersistence: Boolean = false

    @Option(name = arrayOf("--no-amqp-acceptor"), description = "Disable the AMQP specific acceptor.")
    internal var noAmqpAcceptor: Boolean = false

    @Option(name = arrayOf("--no-mqtt-acceptor"), description = "Disable the MQTT specific acceptor.")
    internal var noMqttAcceptor: Boolean = false

    @Option(name = arrayOf("--no-stomp-acceptor"), description = "Disable the STOMP specific acceptor.")
    internal var noStompAcceptor: Boolean = false

    @Option(name = arrayOf("--no-hornetq-acceptor"), description = "Disable the HornetQ specific acceptor.")
    internal var noHornetQAcceptor: Boolean = false

    internal var IS_WINDOWS: Boolean = false

    internal var IS_CYGWIN: Boolean = false

    override fun execute(context: ActionContext): Any? {
        checkDirectory()
        super.execute(context)
        return run(context)
    }


    private fun checkDirectory() {

        if (!directory.exists()) {
            val created = directory.mkdirs()
            if (!created) {
                throw RuntimeException(String.format("Unable to create the path '%s'.", directory))
            }
        } else if (!directory.canWrite()) {
            throw RuntimeException(String.format("The path '%s' is not writable.", directory))
        }
    }

    /**
     * This method is made public for the testsuite
     */
    fun openStream(source: String): InputStream {
        return this.javaClass.getResourceAsStream(source)
    }

    @Throws(Exception::class)
    fun run(context: ActionContext): Any? {
        if (forceLibaio && forceNIO) {
            throw RuntimeException("You can't specify --nio and --aio in the same execution.")
        }
        IS_WINDOWS = System.getProperty("os.name").toLowerCase().trim { it <= ' ' }.startsWith("win")
        IS_CYGWIN = IS_WINDOWS && "cygwin" == System.getenv("OSTYPE")

        requireLogin?.let { requireLogin ->
            if (requireLogin) {
                allowAnonymous = false
            }
        }

        context.out.println(String.format("Creating ActiveMQ Artemis instance at: %s", directory.canonicalPath))
        val filters = mutableMapOf("\${master-slave}" to if (slave) "slave" else "master",
                "\${failover-on-shutdown}" to if (failoverOnShutodwn) "true" else "false",
                "\${persistence-enabled}" to if (disablePersistence) "false" else "true")

        if (replicated) {
            filters["\${replicated.settings}"] = applyFilters(readTextFile(ETC_REPLICATED_SETTINGS_TXT), filters)
        } else {
            filters["\${replicated.settings}"] = ""
        }
        if (sharedStore) {
            clustered = true
            filters["\${shared-store.settings}"] = applyFilters(readTextFile(ETC_SHARED_STORE_SETTINGS_TXT), filters)
        } else {
            filters["\${shared-store.settings}"] = ""
        }

        val aio = if (IS_WINDOWS || !supportsLibaio()) {
            filters["\${journal.settings}"] = "NIO"
            false
        } else {
            filters["\${journal.settings}"] = "ASYNCIO"
            true
        }

        if (sslKey != null) {
            filters["\${web.protocol}"] = "https"
            var extraWebAttr = " keyStorePath=\"$sslKey\" keyStorePassword=\"$sslKeyPassword\""
            if (useClientAuth) {
                extraWebAttr += " clientAuth=\"true\" trustStorePath=\"$sslTrust\" trustStorePassword=\"$sslTrustPassword\""
            }
            filters["\${extra.web.attributes}"] = extraWebAttr
        } else {
            filters["\${web.protocol}"] = "http"
            filters["\${extra.web.attributes}"] = ""
        }
        filters.put("\${user}", System.getProperty("user.name", ""))
        filters.put("\${default.port}", (defaultPort + portOffset).toString())
        filters.put("\${amqp.port}", (AMQP_PORT + portOffset).toString())
        filters.put("\${stomp.port}", (STOMP_PORT + portOffset).toString())
        filters.put("\${hq.port}", (HQ_PORT + portOffset).toString())
        filters.put("\${mqtt.port}", (MQTT_PORT + portOffset).toString())
        filters.put("\${http.port}", (httpPort + portOffset).toString())
        filters.put("\${data.dir}", data)
        filters.put("\${max-hops}", maxHops.toString())

        filters.put("\${message-load-balancing}", messageLoadBalancing.toString())
        filters.put("\${user}", user)
        filters.put("\${password}", password)
        filters.put("\${role}", role)
        if (clustered) {
            filters["\$host"] = getHostForClustered()
            name = name ?: getHostForClustered()
            val connectorSettings = readTextFile(ETC_CONNECTOR_SETTINGS_TXT).apply {
                applyFilters(this, filters)
            }
            name?.let { filters.put("\${name}", it) }
            filters["\${connector-config.settings}"] = connectorSettings
            filters["\${cluster-security.settings}"] = readTextFile(ETC_CLUSTER_SECURITY_SETTINGS_TXT)
            filters["\${cluster.settings}"] = applyFilters(readTextFile(ETC_CLUSTER_SETTINGS_TXT), filters)
            filters["\${cluster-user}"] = clusterUser
            filters["\${cluster-password}"] = clusterPassword
        } else {
            if (name == null) {
                name = host
            }
            name?.let { filters["\${name}"] = it }

            filters["\${host}"] = host
            filters["\${connector-config.settings}"] = ""
            filters["\${cluster-security.settings}"] = ""
            filters["\${cluster.settings}"] = ""
            filters["\${cluster-user}"] = ""
            filters["\${cluster-password}"] = ""
        }
        applyJMSObjects(filters)

        if (home != null) {
            filters["\$home"] = path(home!!, false)
        }
        filters["\${artemis.home}"] = path(home.toString(), false)
        filters["\${artemis.instance}"] = path(directory, false)
        filters["\${artemis.instance.name}"] = directory.name
        filters["\${java.home}"] = path(System.getProperty("java.home"), false)

        File(directory, "bin").run { mkdirs() }
        File(directory, "etc").run { mkdirs() }
        File(directory, "log").run { mkdirs() }
        File(directory, "tmp").run { mkdirs() }
        val dataFolder = File(directory, "data").apply { mkdirs() }

        filters["\${logmanager}"] = getLogManager()
        filters["\${java-opts}"] = javaOptions
        if (allowAnonymous!!) {
            write(ETC_LOGIN_CONFIG_WITH_GUEST, filters, false)
            File(directory, ETC_LOGIN_CONFIG_WITH_GUEST).renameTo(File(directory, ETC_LOGIN_CONFIG))
        } else {
            write(ETC_LOGIN_CONFIG_WITHOUT_GUEST, filters, false)
            File(directory, ETC_LOGIN_CONFIG_WITHOUT_GUEST).renameTo(File(directory, ETC_LOGIN_CONFIG))
        }
        write(ETC_ARTEMIS_ROLES_PROPERTIES, filters, false)
        if (IS_WINDOWS) {
            write(BIN_ARTEMIS_CMD, null, false)
            write(BIN_ARTEMIS_SERVICE_EXE)
            write(BIN_ARTEMIS_SERVICE_XML, filters, false)
            write(ETC_ARTEMIS_PROFILE_CMD, filters, false)
        }
        if (!IS_WINDOWS || IS_CYGWIN) {
            write(BIN_ARTEMIS, filters, true)
            makeExec(BIN_ARTEMIS)
            write(BIN_ARTEMIS_SERVICE, filters, true)
            makeExec(BIN_ARTEMIS_SERVICE)
            write(ETC_ARTEMIS_PROFILE, filters, true)
        }
        write(ETC_LOGGING_PROPERTIES, null, false)

        if (noWeb) {
            filters["\${bootstrap-web-settings}"] = ""
        } else {
            filters["\${bootstrap-web-settings}"] = applyFilters(readTextFile(ETC_BOOTSTRAP_WEB_SETTINGS_TXT), filters)
        }
        if (noAmqpAcceptor) {
            filters["\${amqp-acceptor}"] = ""
        } else {
            filters["\${amqp-acceptor}"] = applyFilters(readTextFile(ETC_AMQP_ACCEPTOR_TXT), filters)
        }
        if (noMqttAcceptor) {
            filters["\${mqtt-acceptor}"] = ""
        } else {
            filters["\${mqtt-acceptor}"] = applyFilters(readTextFile(ETC_MQTT_ACCEPTOR_TXT), filters)
        }
        if (noStompAcceptor) {
            filters["\${stomp-acceptor}"] = ""
        } else {
            filters["\${stomp-acceptor}"] = applyFilters(readTextFile(ETC_STOMP_ACCEPTOR_TXT), filters)
        }
        if (noHornetQAcceptor) {
            filters["\${hornetq-acceptor}"] = ""
        } else {
            filters["\${hornetq-acceptor}"] = applyFilters(readTextFile(ETC_HORNETQ_ACCEPTOR_TXT), filters)
        }
        performAutoTune(filters, aio, dataFolder)
        write(ETC_BROKER_XML, filters, false)
        write(ETC_ARTEMIS_USERS_PROPERTIES, filters, false)

        // we want this variable to remain unchanged so that it will use the value set in the profile
        filters.remove("\${artemis.instance}")
        write(ETC_BOOTSTRAP_XML, filters, false)
        context.out.println("")
        context.out.println("You can now start the broker by executing:  ")
        context.out.println("")
        context.out.println(String.format("   \"%s\" run", path(File(directory, "bin/artemis"), true)))
        var service = File(directory, BIN_ARTEMIS_SERVICE)
        context.out.println("")

        if (!IS_WINDOWS || IS_CYGWIN) {
            context.out.println("Or you can run the broker in the background using:")
            context.out.println("")
            context.out.println(String.format("   \"%s\" start", path(service, true)))
            context.out.println("")
        }

        if (IS_WINDOWS) {
            service = File(directory, BIN_ARTEMIS_SERVICE_EXE)
            context.out.println("Or you can setup the broker as Windows service and run it in the background:")
            context.out.println("")
            context.out.println(String.format("   \"%s\" install", path(service, true)))
            context.out.println(String.format("   \"%s\" start", path(service, true)))
            context.out.println("")
            context.out.println("   To stop the windows service:")
            context.out.println(String.format("      \"%s\" stop", path(service, true)))
            context.out.println("")
            context.out.println("   To uninstall the windows service")
            context.out.println(String.format("      \"%s\" uninstall", path(service, true)))
        }

        return null
    }


    fun getHostForClustered(): String {
        if (host == "0.0.0.0") {
            host = input("--host", "Host $host is not valid for clustering, please provide a valid IP or hostname", "localhost")
        }

        return host
    }

    @Throws(IOException::class)
    private fun getLogManager(): String {
        var logManager = ""
        val dir = File(path(home.toString(), false) + "/lib")

        val matches = dir.listFiles { dir, name ->
            name.startsWith("jboss-logmanager") && name.endsWith(".jar")
        }

        if (matches != null && matches.size > 0) {
            logManager = matches[0].name
        }

        return logManager
    }

    /**
     * It will create the jms configurations
     */
    private fun applyJMSObjects(filters: MutableMap<String, String>) {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        printWriter.println()

        for (str in getQueueList()) {
            printWriter.println("      <queue name=\"$str\"/>")
        }
        for (str in getTopicList()) {
            printWriter.println("      <topic name=\"$str\"/>")
        }
        filters["\${jms-list.settings}"] = writer.toString()
    }

    private fun performAutoTune(filters: MutableMap<String, String>, aio: Boolean, dataFolder: File) {
        if (noAutoTune) {
            filters.put("\${journal-buffer.settings}", "")
        } else {
            try {
                val writes = 250
                println("")
                println("Auto tuning journal ...")

                val time = SyncCalculation.syncTest(dataFolder, 4096, writes, 5, verbose, aio)
                val nanoseconds = SyncCalculation.toNanos(time, writes.toLong())
                val writesPerMillisecond = writes.toDouble() / time.toDouble()

                val writesPerMillisecondStr = DecimalFormat("###.##").format(writesPerMillisecond)

                val syncFilter = mutableMapOf<String, String>()
                syncFilter["\${nanoseconds}"] = nanoseconds.toString()
                syncFilter["\${writesPerMillisecond}"] = writesPerMillisecondStr

                println("done! Your system can make " + writesPerMillisecondStr +
                        " writes per millisecond, your journal-buffer-timeout will be " + nanoseconds)

                filters["\${journal-buffer.settings}"] = applyFilters(readTextFile(ETC_JOURNAL_BUFFER_SETTINGS), syncFilter)

            } catch (e: Exception) {
                filters.put("\${journal-buffer.settings}", "")
                e.printStackTrace()
                System.err.println("Couldn't perform sync calculation, using default values")
            }

        }
    }


    fun supportsLibaio(): Boolean {
        if (forceLibaio) {
            // forcing libaio
            return true
        } else if (forceNIO) {
            // forcing NIO
            return false
        } else if (LibaioContext.isLoaded()) {
            LibaioContext<SubmitInfo>(1, true).use({ context ->
                val tmpFile = File(directory, "validateAIO.bin")
                var supportsLibaio = true
                try {
                    val file = context.openFile(tmpFile, true)
                    file.close()
                } catch (e: Exception) {
                    supportsLibaio = false
                }
                tmpFile.delete()
                if (!supportsLibaio) {
                    System.err.println("The filesystem used on $directory doesn't support libAIO and O_DIRECT files, switching journal-type to NIO")
                }
                return supportsLibaio
            })
        } else {
            return false
        }
    }

    @Throws(IOException::class)
    private fun makeExec(path: String) {
        FileUtil.makeExec(File(directory, path))
    }

    private fun getQueueList(): Array<String> {
        if (queues == null) {
            return emptyArray()
        } else {
            return ",".toRegex().split(queues!!, 0).dropLastWhile { it.isEmpty() }.toTypedArray()
        }
    }

    private fun getTopicList(): Array<String> {
        if (topics == null) {
            return emptyArray()
        } else {
            return ",".toRegex().split(topics!!, 0).dropLastWhile { it.isEmpty() }.toTypedArray()
        }
    }


    @Throws(IOException::class)
    internal fun path(value: String, unixPaths: Boolean): String {
        return path(File(value), unixPaths)
    }

    @Throws(IOException::class)
    private fun path(value: File, unixPaths: Boolean): String {
        if (unixPaths && IS_CYGWIN) {
            return value.canonicalPath
        } else {
            return value.canonicalPath
        }
    }

    @Throws(Exception::class)
    private fun write(source: String, filters: MutableMap<String, String>?, unixTarget: Boolean) {
        write(source, File(directory, source), filters, unixTarget)
    }

    @Throws(Exception::class)
    private fun write(source: String,
                      target: File,
                      filters: MutableMap<String, String>?,
                      unixTarget: Boolean) {
        if (target.exists() && !force) {
            throw CLIException(String.format("The file '%s' already exists.  Use --force to overwrite.", target))
        }

        var content = applyFilters(readTextFile(source), filters)

        // and then writing out in the new target encoding..  Let's also replace \n with the values
        // that is correct for the current platform.
        val separator = if (unixTarget && IS_CYGWIN) "\n" else System.getProperty("line.separator")
        content = content.replace("\\r?\\n".toRegex(), Matcher.quoteReplacement(separator))
        val `in` = ByteArrayInputStream(content.toByteArray(charset(encoding)))

        FileOutputStream(target).use { fout -> copy(`in`, fout) }
    }

    @Throws(IOException::class)
    private fun applyFilters(content: String, filters: Map<String, String>?): String {
        var nContent = content

        if (filters != null) {
            for ((key, value) in filters) {
                nContent = replace(nContent, key, value)
            }
        }
        return nContent
    }

    @Throws(IOException::class)
    private fun readTextFile(source: String): String {
        val out = ByteArrayOutputStream()
        openStream(source).use { `in` -> copy(`in`, out) }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun write(source: String) {
        val target = File(directory, source)
        if (target.exists() && !force) {
            throw RuntimeException(String.format("The file '%s' already exists.  Use --force to overwrite.", target))
        }
        FileOutputStream(target).use({ fout -> openStream(source).use({ `in` -> copy(`in`, fout) }) })
    }


    private fun replace(content: String, key: String, value: String): String {
        return content.replace(Pattern.quote(key).toRegex(), Matcher.quoteReplacement(value))
    }

    @Throws(IOException::class)
    private fun copy(`is`: InputStream, os: OutputStream) {
        val buffer = ByteArray(1024 * 4)
        var c = `is`.read(buffer)
        while (c >= 0) {
            os.write(buffer, 0, c)
            c = `is`.read(buffer)
        }
    }

    companion object {
        const private val DEFAULT_PORT = 61616
        const private val AMQP_PORT = 5672
        const private val STOMP_PORT = 61613
        const private val HQ_PORT = 5445
        const private val MQTT_PORT = 1883

        const val HTTP_PORT = 8161
        const val BIN_ARTEMIS_CMD = "bin/artemis.cmd"
        const val BIN_ARTEMIS_SERVICE_EXE = "bin/artemis-service.exe"
        const val BIN_ARTEMIS_SERVICE_XML = "bin/artemis-service.xml"
        const val ETC_ARTEMIS_PROFILE_CMD = "etc/artemis.profile.cmd"
        const val BIN_ARTEMIS = "bin/artemis"
        const val BIN_ARTEMIS_SERVICE = "bin/artemis-service"
        const val ETC_ARTEMIS_PROFILE = "etc/artemis.profile"
        const val ETC_LOGGING_PROPERTIES = "etc/logging.properties"
        const val ETC_BOOTSTRAP_XML = "etc/bootstrap.xml"
        const val ETC_BROKER_XML = "etc/broker.xml"

        const val ETC_ARTEMIS_ROLES_PROPERTIES = "etc/artemis-roles.properties"
        const val ETC_ARTEMIS_USERS_PROPERTIES = "etc/artemis-users.properties"
        const val ETC_LOGIN_CONFIG = "etc/login.config"
        const val ETC_LOGIN_CONFIG_WITH_GUEST = "etc/login-with-guest.config"
        const val ETC_LOGIN_CONFIG_WITHOUT_GUEST = "etc/login-without-guest.config"
        const val ETC_REPLICATED_SETTINGS_TXT = "etc/replicated-settings.txt"
        const val ETC_SHARED_STORE_SETTINGS_TXT = "etc/shared-store-settings.txt"
        const val ETC_CLUSTER_SECURITY_SETTINGS_TXT = "etc/cluster-security-settings.txt"
        const val ETC_CLUSTER_SETTINGS_TXT = "etc/cluster-settings.txt"
        const val ETC_CONNECTOR_SETTINGS_TXT = "etc/connector-settings.txt"
        const val ETC_BOOTSTRAP_WEB_SETTINGS_TXT = "etc/bootstrap-web-settings.txt"
        const val ETC_JOURNAL_BUFFER_SETTINGS = "etc/journal-buffer-settings.txt"
        const val ETC_AMQP_ACCEPTOR_TXT = "etc/amqp-acceptor.txt"
        const val ETC_HORNETQ_ACCEPTOR_TXT = "etc/hornetq-acceptor.txt"
        const val ETC_MQTT_ACCEPTOR_TXT = "etc/mqtt-acceptor.txt"
        const val ETC_STOMP_ACCEPTOR_TXT = "etc/stomp-acceptor.txt"
    }
}