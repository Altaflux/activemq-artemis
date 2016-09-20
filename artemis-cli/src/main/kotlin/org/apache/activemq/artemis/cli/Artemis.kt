package org.apache.activemq.artemis.cli

import io.airlift.airline.Cli
import org.apache.activemq.artemis.cli.commands.*
import org.apache.activemq.artemis.cli.commands.destination.CreateDestination
import org.apache.activemq.artemis.cli.commands.destination.DeleteDestination
import org.apache.activemq.artemis.cli.commands.destination.HelpDestination
import org.apache.activemq.artemis.cli.commands.messages.Browse
import org.apache.activemq.artemis.cli.commands.messages.Consumer
import org.apache.activemq.artemis.cli.commands.messages.Producer
import org.apache.activemq.artemis.cli.commands.tools.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Artemis is the main CLI entry point for managing/running a broker.
 *
 * Want to start or debug a broker from an IDE?  This is probably the best class to
 * run.  Make sure set the -Dartemis.instance=path/to/instance system property.
 * You should also use the 'apache-artemis' module for the class path since that
 * includes all artemis modules.
 */
object Artemis {

    @Throws(Exception::class)
    @JvmStatic fun main(vararg args: String) {
        val home = System.getProperty("artemis.home")
        val fileHome = if (home != null) File(home) else null
        val instance = System.getProperty("artemis.instance")
        val fileInstance = if (instance != null) File(instance) else null

        execute(fileHome, fileInstance, *args)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun internalExecute(vararg args: String): Any? {
        return internalExecute(null, null, *args)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun execute(artemisHome: File?, artemisInstance: File?, args: List<String>): Any? {
        return execute(artemisHome, artemisInstance, *args.toTypedArray())
    }


    @Throws(Exception::class)
    @JvmStatic
    fun execute(artemisHome: File?, artemisInstance: File?, vararg args: String): Any? {
        try {
            return internalExecute(artemisHome, artemisInstance, *args)
        } catch (configException: ConfigurationException) {
            System.err.println(configException.message)
            println()
            println("Configuration should be specified as 'scheme:location'. Default configuration is 'xml:\${ARTEMIS_INSTANCE}/etc/bootstrap.xml'")
            return configException
        } catch (cliException: CLIException) {
            System.err.println(cliException.message)
            return cliException
        } catch (e: NullPointerException) {
            // Yeah.. I really meant System.err..
            // this is the CLI and System.out and System.err are common places for interacting with the user
            // this is a programming error that must be visualized and corrected
            e.printStackTrace()
            return e
        } catch (re: RuntimeException) {
            System.err.println(re.message)
            println()

            val parser = builder(null).build()

            parser.parse("help").execute(ActionContext.system())
            return re
        }

    }


    /** This method is used to validate exception returns.
     * Useful on test cases  */
    @Throws(Exception::class)
    fun internalExecute(artemisHome: File?, artemisInstance: File?, vararg args: String): Any? {
        val action = builder(artemisInstance).build().parse(*args)
        action.setHomeValues(artemisHome, artemisInstance)

        if (action.verbose) {
            print("Executing " + action.javaClass.name + " ")
            for (arg in args) {
                print(arg + " ")
            }
            println()
            println("Home::" + action.brokerHome + ", Instance::" + action.brokerInstance)
        }

        return action.execute(ActionContext.system())
    }

    private fun builder(artemisInstance: File?): Cli.CliBuilder<Action> {
        val instance = if (artemisInstance != null) artemisInstance.absolutePath else System.getProperty("artemis.instance")
        var builder: Cli.CliBuilder<Action> = Cli.builder<Action>("artemis")
                .withDescription("ActiveMQ Artemis Command Line")
                .withCommand(HelpAction::class.java)
                .withCommand(Producer::class.java)
                .withCommand(Consumer::class.java)
                .withCommand(Browse::class.java)
                .withDefaultCommand(HelpAction::class.java)

        builder.withGroup("destination").withDescription("Destination tools group (create|delete) (example ./artemis destination create)").withDefaultCommand(HelpDestination::class.java).withCommands(CreateDestination::class.java, DeleteDestination::class.java)

        if (instance != null) {
            builder.withGroup("data").withDescription("data tools group (print|exp|imp|exp|encode|decode|compact) (example ./artemis data print)")
                    .withDefaultCommand(HelpData::class.java)
                    .withCommands(PrintData::class.java, XmlDataExporter::class.java, XmlDataImporter::class.java, DecodeJournal::class.java, EncodeJournal::class.java, CompactJournal::class.java)
            builder = builder.withCommands(Run::class.java, Stop::class.java, Kill::class.java)
        } else {
            builder.withGroup("data").withDescription("data tools group (print) (example ./artemis data print)")
                    .withDefaultCommand(HelpData::class.java)
                    .withCommands(PrintData::class.java)
            builder = builder.withCommand(Create::class.java)
        }

        return builder
    }


    @Throws(Exception::class)
    @JvmStatic
    fun printBanner() {
        copy(Artemis::class.java.getResourceAsStream("banner.txt"), System.out)
    }

    @Throws(Exception::class)
    private fun copy(`in`: InputStream, out: OutputStream): Long {
        val buffer = ByteArray(1024)
        var len = `in`.read(buffer)
        while (len != -1) {
            out.write(buffer, 0, len)
            len = `in`.read(buffer)
        }
        return len.toLong()
    }
}