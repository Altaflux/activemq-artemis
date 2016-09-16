package org.apache.activemq.artemis.cli.commands.destination

import io.airlift.airline.Option
import org.apache.activemq.artemis.api.core.client.ActiveMQClient
import org.apache.activemq.artemis.api.core.client.ClientMessage
import org.apache.activemq.artemis.api.core.client.ClientRequestor
import org.apache.activemq.artemis.api.core.management.ManagementHelper
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper
import org.apache.activemq.artemis.cli.commands.InputAbstract
import org.apache.activemq.artemis.core.client.impl.ServerLocatorImpl
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory
import org.apache.activemq.artemis.jms.client.ActiveMQSession
import javax.jms.Message
import javax.jms.QueueRequestor
import javax.jms.Session


abstract class DestinationAction : InputAbstract() {

    @Option(name = arrayOf("--type"), description = "type of destination to be created (one of jms-queue, topic and core-queue, default jms-queue")
    var destType: String? = JMS_QUEUE

    @Option(name = arrayOf("--url"), description = "URL towards the broker. (default: tcp://localhost:61616)")
    var brokerURL = "tcp://localhost:61616"

    @Option(name = arrayOf("--user"), description = "User used to connect")
    var user: String? = null

    @Option(name = arrayOf("--password"), description = "Password used to connect")
    var password: String? = null

    @Option(name = arrayOf("--name"), description = "destination name")
    var name: String? = null
        get() {
            if (field.isNullOrEmpty()) {
                field = input("--name", "Please provide the destination name:", "")
            }
            return field
        }


    companion object {

        fun performJmsManagement(brokerUrl: String, user: String?, password: String?, cb: ManagementCallback<Message>) {
            ActiveMQConnectionFactory(brokerUrl, user, password).use { factory ->
                factory.createConnection().use { connection ->
                    connection.createSession(false, Session.AUTO_ACKNOWLEDGE).use { session ->
                        val managementQueue = ActiveMQJMSClient.createQueue("activemq.management")
                        val requestor = QueueRequestor(session as ActiveMQSession, managementQueue)
                        connection.start()

                        val message = session.createMessage()
                        cb.setUpInvocation(message)
                        val reply = requestor.request(message)
                        if (JMSManagementHelper.hasOperationSucceeded(reply)) {
                            cb.requestSuccessful(reply)
                        } else {
                            cb.requestFailed(reply)
                        }
                    }
                }
            }
        }

        fun performCoreManagement(brokerUrl: String, user: String?, password: String?, cb: ManagementCallback<ClientMessage>) {
            ServerLocatorImpl.newLocator(brokerUrl).use { locator ->
                locator.createSessionFactory().use { sessionFactory ->
                    sessionFactory.createSession(user, password, false, true, true, false, ActiveMQClient.DEFAULT_ACK_BATCH_SIZE).use { session ->
                        session.start()
                        val requestor = ClientRequestor(session, "jms.queue.activemq.management")
                        val message = session.createMessage(false)
                        cb.setUpInvocation(message)
                        val reply = requestor.request(message)
                        if (ManagementHelper.hasOperationSucceeded(reply)) {
                            cb.requestSuccessful(reply)
                        } else {
                            cb.requestFailed(reply)
                        }
                    }
                }
            }
        }


        private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
            var closed = false
            try {
                return block(this)
            } catch (e: Exception) {
                closed = true
                try {
                    close()
                } catch (closeException: Exception) {
                 //    e.addSuppressed(closeException)
                }
                throw e
            } finally {
                if (!closed) {
                    close()
                }
            }
        }

        const val JMS_QUEUE = "jms-queue"
        const val JMS_TOPIC = "topic"
        const val CORE_QUEUE = "core-queue"
    }

    interface ManagementCallback<in T> {
        fun setUpInvocation(message: T)
        fun requestSuccessful(reply: T)
        fun requestFailed(reply: T)
    }
}


