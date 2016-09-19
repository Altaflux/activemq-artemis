package org.apache.activemq.artemis.factory

import org.apache.activemq.artemis.dto.JaasSecurityDTO
import org.apache.activemq.artemis.dto.SecurityDTO
import org.apache.activemq.artemis.spi.core.security.ActiveMQJAASSecurityManager
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager


class JaasSecurityHandler : SecurityHandler {

    override fun createSecurityManager(securityDTO: SecurityDTO): ActiveMQSecurityManager {
        val jaasSecurity = securityDTO as JaasSecurityDTO
        val securityManager = ActiveMQJAASSecurityManager(jaasSecurity.domain, jaasSecurity.certificateDomain)
        return securityManager
    }
}