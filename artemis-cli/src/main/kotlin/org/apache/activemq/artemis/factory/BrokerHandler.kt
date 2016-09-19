package org.apache.activemq.artemis.factory

import org.apache.activemq.artemis.dto.ServerDTO
import org.apache.activemq.artemis.integration.Broker
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager


interface BrokerHandler {

    fun createServer(brokerDTO: ServerDTO, security: ActiveMQSecurityManager): Broker
}