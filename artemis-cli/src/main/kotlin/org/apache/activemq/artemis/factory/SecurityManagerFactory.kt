package org.apache.activemq.artemis.factory

import org.apache.activemq.artemis.dto.SecurityDTO
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager
import org.apache.activemq.artemis.utils.FactoryFinder
import javax.xml.bind.annotation.XmlRootElement


object SecurityManagerFactory {

    @Throws(Exception::class)
    fun create(config: SecurityDTO?): ActiveMQSecurityManager {
        if (config != null) {
            val finder = FactoryFinder("META-INF/services/org/apache/activemq/artemis/broker/security/")
            val securityHandler = finder.newInstance(config.javaClass.getAnnotation(XmlRootElement::class.java).name) as SecurityHandler
            return securityHandler.createSecurityManager(config)
        } else {
            throw Exception("No security manager configured!")
        }
    }
}