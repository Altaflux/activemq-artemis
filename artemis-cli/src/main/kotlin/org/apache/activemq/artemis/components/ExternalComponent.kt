package org.apache.activemq.artemis.components

import org.apache.activemq.artemis.core.server.ActiveMQComponent
import org.apache.activemq.artemis.dto.ComponentDTO


interface ExternalComponent : ActiveMQComponent {
    @Throws(Exception::class)
    fun configure(config: ComponentDTO, artemisInstance: String, artemisHome: String)
}