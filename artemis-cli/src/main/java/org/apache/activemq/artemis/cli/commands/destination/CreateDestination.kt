package org.apache.activemq.artemis.cli.commands.destination

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.api.core.client.ClientMessage
import org.apache.activemq.artemis.api.core.management.ManagementHelper
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper
import org.apache.activemq.artemis.cli.commands.ActionContext
import javax.jms.Message

@Command(name = "create", description = "create a queue or topic")
class CreateDestination : DestinationAction() {

    @Option(name = arrayOf("--filter"), description = "queue's filter string (default null)")
    var filter: String? = null

    @Option(name = arrayOf("--address"), description = "address of the core queue (default queue's name)")
    var address: String? = null
        get() {
            if (field.isNullOrEmpty()) {
                field = name
            }
            return field!!.trim()
        }

    @Option(name = arrayOf("--durable"), description = "whether the queue is durable or not (default false)")
    var durable: Boolean = false

    @Option(name = arrayOf("--bindings"), description = "comma separated jndi binding names (default null)")
    var bindings: String? = ""


    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        when (destType) {
            JMS_QUEUE -> createJmsQueue(context)
            CORE_QUEUE -> createCoreQueue(context)
            JMS_TOPIC -> createJmsTopic(context)
            else -> {
                throw IllegalArgumentException("--type can only be one of ${DestinationAction.JMS_QUEUE}, ${DestinationAction.JMS_TOPIC} and ${DestinationAction.CORE_QUEUE}")
            }
        }
        return null
    }

    private fun createJmsTopic(context: ActionContext) {
        DestinationAction.performJmsManagement(brokerURL, user, password, object : ManagementCallback<Message> {
            override fun setUpInvocation(message: Message) {
                JMSManagementHelper.putOperationInvocation(message, "jms.server", "createTopic", name, bindings)
            }

            override fun requestSuccessful(reply: Message) {
                val result = JMSManagementHelper.getResult(reply, Boolean::class.java) as Boolean
                if (result) {
                    context.out.print("Topic $name created successfully.")
                } else {
                    context.err.println("Failed to create topic $name.")
                }
            }

            override fun requestFailed(reply: Message) {
                val errorMsg = JMSManagementHelper.getResult(reply, String::class.java) as String
                context.err.println("Failed to create topic $name. Reason: $errorMsg")
            }
        })
    }

    private fun createCoreQueue(context: ActionContext) {
        DestinationAction.performCoreManagement(brokerURL, user, password, object : ManagementCallback<ClientMessage> {
            override fun setUpInvocation(message: ClientMessage) {
                ManagementHelper.putOperationInvocation(message, "core.server", "createQueue", address, name, filter, durable)
            }

            override fun requestSuccessful(reply: ClientMessage) {
                context.out.println("Core queue $name created successfully.")
            }

            override fun requestFailed(reply: ClientMessage) {
                val errorMsg = ManagementHelper.getResult(reply, String::class.java) as String
                context.err.println("Failed to create queue $name. Reason: $errorMsg")
            }
        })
    }

    private fun createJmsQueue(context: ActionContext) {
        DestinationAction.performJmsManagement(brokerURL, user, password, object : ManagementCallback<Message> {
            override fun setUpInvocation(message: Message) {
                JMSManagementHelper.putOperationInvocation(message, "jms.server", "createQueue", name, bindings, filter, durable)
            }

            override fun requestSuccessful(reply: Message) {
                val result = JMSManagementHelper.getResult(reply, Boolean::class.java) as Boolean
                if (result) {
                    context.out.print("Jms queue $name created successfully.")
                } else {
                    context.err.println("Failed to create jms queue $name.")
                }
            }

            override fun requestFailed(reply: Message) {
                val errorMsg = JMSManagementHelper.getResult(reply, String::class.java) as String
                context.err.println("Failed to create jms queue $name. Reason: $errorMsg")
            }
        })
    }
}