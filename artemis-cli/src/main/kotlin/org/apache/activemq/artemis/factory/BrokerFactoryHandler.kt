package org.apache.activemq.artemis.factory

import org.apache.activemq.artemis.dto.BrokerDTO
import java.net.URI

interface BrokerFactoryHandler {

    @Throws(Exception::class)
    fun createBroker(brokerURI: URI): BrokerDTO

    @Throws(Exception::class)
    fun createBroker(brokerURI: URI, artemisHome: String?, artemisInstance: String?): BrokerDTO
}