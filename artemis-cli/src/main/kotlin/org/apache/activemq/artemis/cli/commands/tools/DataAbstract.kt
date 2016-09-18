package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.commands.Configurable
import java.io.File

/**
 * Abstract class for places where you need bindings, journal paging and large messages configuration
 */
abstract class DataAbstract : Configurable() {


    @Option(name = arrayOf("--binding"), description = "The folder used for bindings (default from broker.xml)")
    var binding: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = fileConfiguration.bindingsLocation.absolutePath
            }
            field.let { checkIfDirectoryExists(it) }
            return field
        }


    @Option(name = arrayOf("--journal"), description = "The folder used for messages journal (default from broker.xml)")
    var journal: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = fileConfiguration.journalLocation.absolutePath
            }
            field.let { checkIfDirectoryExists(it) }
            return field
        }

    @Option(name = arrayOf("--paging"), description = "The folder used for paging (default from broker.xml)")
    var paging: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = fileConfiguration.pagingLocation.absolutePath
            }
            field.let { checkIfDirectoryExists(it) }
            return field
        }

    @Option(name = arrayOf("--large-messages"), description = "The folder used for large-messages (default from broker.xml)")
    var largeMessages: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                field = fileConfiguration.largeMessagesLocation.absolutePath
            }
            field.let { checkIfDirectoryExists(it) }
            return field
        }

    private fun checkIfDirectoryExists(directory: String) {
        val f = File(directory)
        if (!f.exists()) {
            throw IllegalStateException("Could not find folder: $directory, please pass --bindings, --journal and --paging as arguments")
        }
    }
}