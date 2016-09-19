package org.apache.activemq.artemis.integration

import org.apache.activemq.artemis.core.config.FileDeploymentManager
import org.apache.activemq.artemis.core.config.impl.FileConfiguration
import org.apache.activemq.artemis.core.server.ActiveMQComponent
import org.apache.activemq.artemis.core.server.ActiveMQServer
import org.apache.activemq.artemis.dto.ServerDTO
import org.apache.activemq.artemis.integration.bootstrap.ActiveMQBootstrapLogger
import org.apache.activemq.artemis.jms.server.config.impl.FileJMSConfiguration
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager
import java.lang.management.ManagementFactory


class FileBroker(broker: ServerDTO, val securityManager: ActiveMQSecurityManager) : Broker {
    private val configurationUrl: String
    private var started: Boolean = false
    lateinit var components: Map<String, ActiveMQComponent>

    init {
        configurationUrl = broker.configuration
    }

    override val server: ActiveMQServer
        get() {
            return components["core"] as ActiveMQServer
        }

    override fun start() {
        if (started) return

        val configuration = FileConfiguration()
        val jmsConfiguration = FileJMSConfiguration()

        val fileDeploymentManager = FileDeploymentManager(configurationUrl)
        fileDeploymentManager.addDeployable(configuration).addDeployable(jmsConfiguration)
        fileDeploymentManager.readConfiguration()

        components = fileDeploymentManager.buildService(securityManager, ManagementFactory.getPlatformMBeanServer())
        val componentsByStartOrder = getComponentsByStartOrder(components)
        ActiveMQBootstrapLogger.LOGGER.serverStarting()
        for (component in componentsByStartOrder) {
            component.start()
        }
        started = true
    }

    override fun stop() {
        if (!started) {
            return
        }
        val mqComponents = components.values.toTypedArray()
        for (i in mqComponents.indices.reversed()) {
            mqComponents[i].let {
                it.stop()
            }
        }
        started = false
    }

    override fun isStarted(): Boolean {
        return started
    }


    fun getComponentsByStartOrder(components: Map<String, ActiveMQComponent>): List<ActiveMQComponent> {
        val activeMQComponents = mutableListOf<ActiveMQComponent>()
        val jmsComponent = components["jms"]
        if (jmsComponent != null) {
            activeMQComponents.add(jmsComponent)
        }
        val coreComponent = components["core"]
        if (coreComponent != null) {
            activeMQComponents.add(coreComponent)
        }
        return activeMQComponents
    }
}