package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Arguments
import io.airlift.airline.Help
import io.airlift.airline.Option
import io.airlift.airline.model.GlobalMetadata
import org.apache.activemq.artemis.core.config.FileDeploymentManager
import org.apache.activemq.artemis.core.config.impl.FileConfiguration
import org.apache.activemq.artemis.dto.BrokerDTO
import org.apache.activemq.artemis.factory.BrokerFactory
import org.apache.activemq.artemis.integration.bootstrap.ActiveMQBootstrapLogger
import org.apache.activemq.artemis.jms.server.config.impl.FileJMSConfiguration
import java.io.File
import javax.inject.Inject

abstract class Configurable : ActionAbstract() {


    @Inject
    lateinit var global: GlobalMetadata

    @Arguments(description = "Broker Configuration URI, default 'xml:\${ARTEMIS_INSTANCE}/etc/bootstrap.xml'")
    internal var configuration: String = ""
        get() {
            if (field.isNullOrEmpty()) {
                val xmlFile = File(File(File(brokerInstance), "etc"), "bootstrap.xml")
                val value = "xml:" + xmlFile.toURI().toString().substring("file:".length)
                field = value.replace("\\", "/")
                ActiveMQBootstrapLogger.LOGGER.usingBrokerConfig(configuration)
            }
            return field
        }

    @Option(name = arrayOf("--broker"), description = "This would override the broker configuration from the bootstrap")
    internal var brokerConfig: String? = null

    protected val brokerDTO: BrokerDTO by lazy {
        val newValue = BrokerFactory.createBrokerConfiguration(configuration, brokerHome, brokerInstance)

        brokerConfig?.let {
            if (!brokerConfig!!.startsWith("file:")) {
                brokerConfig = "file:" + brokerConfig
            }
            newValue.server.configuration = brokerConfig!!
        }

        return@lazy newValue
    }


    protected val fileConfiguration: FileConfiguration by lazy {
        if (brokerInstance == null) {
            val defaultLocation = "./data"
            val newFileConfiguration = FileConfiguration()
            newFileConfiguration.bindingsDirectory = defaultLocation + "/bindings"
            newFileConfiguration.journalDirectory = defaultLocation + "/journal"
            newFileConfiguration.largeMessagesDirectory = defaultLocation + "/largemessages"
            newFileConfiguration.pagingDirectory = defaultLocation + "/paging"
            newFileConfiguration.brokerInstance = File(".")
            return@lazy newFileConfiguration
        } else {
            val newFileConfiguration = FileConfiguration()
            val jmsConfiguration = FileJMSConfiguration()
            val serverConfiguration = brokerDTO.server.configuration
            val fileDeploymentManager = FileDeploymentManager(serverConfiguration)
            fileDeploymentManager.addDeployable(newFileConfiguration).addDeployable(jmsConfiguration)
            fileDeploymentManager.readConfiguration()
            newFileConfiguration.brokerInstance = File(brokerInstance)
            return@lazy newFileConfiguration
        }
    }

    protected fun treatError(e: Exception, group: String, command: String) {
        ActiveMQBootstrapLogger.LOGGER.debug(e.message, e)
        System.err.println()
        System.err.println("Error: ${e.message}")
        System.err.println()
        helpGroup(group, command)
    }

    protected fun helpGroup(groupName: String, commandName: String) {
        for (group in global.commandGroups) {
            if (group.name == groupName) {
                for (command in group.commands) {
                    if (command.name == commandName) {
                        Help.help(command)
                    }
                }
                break
            }
        }
    }
}
