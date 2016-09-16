package org.apache.activemq.artemis.cli.commands.destination

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.api.core.client.ClientMessage
import org.apache.activemq.artemis.api.core.management.ManagementHelper
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper
import org.apache.activemq.artemis.cli.commands.ActionContext
import javax.jms.Message

@Command(name = "delete", description = "delete a queue or topic")
class DeleteDestination : DestinationAction() {

    @Option(name = arrayOf("--removeConsumers"), description = "whether deleting destination with consumers or not (default false)")
    var removeConsumers: Boolean = false

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        when (destType) {
            JMS_QUEUE -> deleteJmsQueue(context)
            JMS_TOPIC -> deleteJmsTopic(context)
            CORE_QUEUE -> deleteCoreQueue(context)
            else -> {
                throw IllegalArgumentException("--type can only be one of " + DestinationAction.JMS_QUEUE + ", " + DestinationAction.JMS_TOPIC + " and " + DestinationAction.CORE_QUEUE)
            }
        }
        return null
    }

    private fun deleteJmsTopic(context: ActionContext) {
        DestinationAction.performJmsManagement(brokerURL, user, password, object : ManagementCallback<Message> {
            override fun setUpInvocation(message: Message) {
                JMSManagementHelper.putOperationInvocation(message, "jms.server", "destroyTopic", name, removeConsumers)
            }

            override fun requestSuccessful(reply: Message) {
                val result = JMSManagementHelper.getResult(reply, Boolean::class.java) as Boolean
                if (result) {
                    context.out.println("Topic $name deleted successfully.")
                } else {
                    context.err.println("Failed to delete topic $name")
                }
            }

            override fun requestFailed(reply: Message) {
                val errorMsg = JMSManagementHelper.getResult(reply, String::class.java) as String
                context.err.println("Failed to delete topic $name. Reason: $errorMsg")
            }
        })
    }

    private fun deleteJmsQueue(context: ActionContext) {
        DestinationAction.performJmsManagement(brokerURL, user, password, object : ManagementCallback<Message> {
            override fun setUpInvocation(message: Message) {
                JMSManagementHelper.putOperationInvocation(message, "jms.server", "destroyQueue", name, removeConsumers)
            }

            override fun requestSuccessful(reply: Message) {
                val result = JMSManagementHelper.getResult(reply, Boolean::class.java) as Boolean
                if (result) {
                    context.out.println("Jms queue $name deleted successfully.")
                } else {
                    context.err.println("Failed to delete queue $name")
                }
            }

            override fun requestFailed(reply: Message) {
                val errorMsg = JMSManagementHelper.getResult(reply, String::class.java) as String
                context.err.println("Failed to create $name with reason: $errorMsg")
            }
        })
    }

    private fun deleteCoreQueue(context: ActionContext) {
        DestinationAction.performCoreManagement(brokerURL, user, password, object : ManagementCallback<ClientMessage> {
            override fun setUpInvocation(message: ClientMessage) {
                ManagementHelper.putOperationInvocation(message, "core.server", "destroyQueue", name)
            }

            override fun requestSuccessful(reply: ClientMessage) {
                context.out.println("Queue $name deleted successfully.")
            }

            override fun requestFailed(reply: ClientMessage) {
                val errMsg = ManagementHelper.getResult(reply, String::class.java) as String
                context.err.println("Failed to delete queue $name. Reason: $errMsg")
            }
        })
    }
}