package org.apache.activemq.artemis.factory

import org.apache.activemq.artemis.cli.ConfigurationException
import org.apache.activemq.artemis.dto.BrokerDTO
import org.apache.activemq.artemis.dto.ServerDTO
import org.apache.activemq.artemis.integration.Broker
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager
import org.apache.activemq.artemis.utils.FactoryFinder
import java.io.File
import java.io.IOException
import java.net.URI


object BrokerFactory {


    @Throws(Exception::class)
    @JvmStatic
    fun createBrokerConfiguration(configURI: URI): BrokerDTO {
        return createBrokerConfiguration(configURI, null, null)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun createBrokerConfiguration(configuration: String): BrokerDTO {
        return createBrokerConfiguration(URI(configuration), null, null)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun createBrokerConfiguration(configuration: String, artemisHome: String?, artemisInstance: String?): BrokerDTO {
        return createBrokerConfiguration(URI(configuration), artemisHome, artemisInstance)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun createBrokerConfiguration(configURI: URI, artemisHome: String?, artemisInstance: String?): BrokerDTO {
        if (configURI.scheme == null) {
            throw ConfigurationException("Invalid configuration URI, no scheme specified: " + configURI)
        }

        val factory = try {
            val finder = FactoryFinder("META-INF/services/org/apache/activemq/artemis/broker/")
            finder.newInstance(configURI.scheme) as BrokerFactoryHandler
        } catch (ioe: IOException) {
            throw ConfigurationException("Invalid configuration URI, can't find configuration scheme: " + configURI.scheme)
        }

        return factory.createBroker(configURI, artemisHome, artemisInstance)
    }


    internal fun fixupFileURI(value: String?): String? {
        var nValue = value
        if (nValue != null && nValue.startsWith("file:")) {
            nValue = nValue.substring("file:".length)
            nValue = File(nValue).toURI().toString()
        }
        return nValue
    }

    @Throws(Exception::class)
    @JvmStatic
    fun createServer(brokerDTO: ServerDTO, security: ActiveMQSecurityManager): Broker {
        if (brokerDTO.configuration != null) {
            val handler: BrokerHandler
            val configURI = brokerDTO.configurationURI

            try {
                val finder = FactoryFinder("META-INF/services/org/apache/activemq/artemis/broker/server/")
                handler = finder.newInstance(configURI.scheme) as BrokerHandler
            } catch (ioe: IOException) {
                throw ConfigurationException("Invalid configuration URI, can't find configuration scheme: " + configURI.scheme)
            }

            return handler.createServer(brokerDTO, security)
        }
        throw ConfigurationException("Configuration not defined")
    }
}