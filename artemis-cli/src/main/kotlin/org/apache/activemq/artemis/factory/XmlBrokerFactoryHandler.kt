package org.apache.activemq.artemis.factory

import org.apache.activemq.artemis.cli.ConfigurationException
import org.apache.activemq.artemis.dto.BrokerDTO
import org.apache.activemq.artemis.dto.XmlUtil
import java.io.File
import java.net.URI


class XmlBrokerFactoryHandler : BrokerFactoryHandler {

    override fun createBroker(brokerURI: URI): BrokerDTO {
        return createBroker(brokerURI, null, null)
    }

    override fun createBroker(brokerURI: URI, artemisHome: String?, artemisInstance: String?): BrokerDTO {
        val file = File(brokerURI.schemeSpecificPart)
        if (!file.exists()) {
            throw ConfigurationException("Invalid configuration URI, can't find file: " + file.name)
        }
        return XmlUtil.decode(BrokerDTO::class.java, file, artemisHome, artemisInstance)
    }
}