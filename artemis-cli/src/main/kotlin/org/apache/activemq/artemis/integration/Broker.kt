package org.apache.activemq.artemis.integration

import org.apache.activemq.artemis.core.server.ActiveMQComponent
import org.apache.activemq.artemis.core.server.ActiveMQServer


interface Broker : ActiveMQComponent {
    val server: ActiveMQServer
}