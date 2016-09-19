package org.apache.activemq.artemis.factory

import org.apache.activemq.artemis.dto.SecurityDTO
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager


interface SecurityHandler {

    @Throws(Exception::class)
    fun createSecurityManager(securityDTO: SecurityDTO): ActiveMQSecurityManager
}