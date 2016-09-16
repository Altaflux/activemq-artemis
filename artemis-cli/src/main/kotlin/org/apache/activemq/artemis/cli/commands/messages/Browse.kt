package org.apache.activemq.artemis.cli.commands.messages

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.cli.commands.util.ConsumerThread
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory
import org.apache.activemq.artemis.jms.client.ActiveMQDestination
import javax.jms.Session

@Command(name = "browser", description = "It will send consume messages from an instance")
class Browse : DestAbstract() {

    @Option(name = arrayOf("--filter"), description = "filter to be used with the consumer")
    var filter: String? = null

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        println("Consumer:: filter = " + filter)
        val factory = ActiveMQConnectionFactory(brokerURL, user, password)
        val dest = ActiveMQDestination.createDestination(this.destination, ActiveMQDestination.QUEUE_TYPE)
        factory.createConnection().use { connection ->
            val threadsArray = arrayOfNulls<ConsumerThread>(threads)
            for (i in 0..threads -1) {
                val session = if (txBatchSize > 0) connection.createSession(true, Session.SESSION_TRANSACTED) else connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
                threadsArray[i] = ConsumerThread(session, dest, i)
                threadsArray[i]!!.setVerbose(verbose).setSleep(sleep).setMessageCount(messageCount).setFilter(filter).isBrowse = true
            }
            for (thread in threadsArray) {
                thread!!.start()
            }
            connection.start()
            var received = 0
            for (thread in threadsArray) {
                thread!!.join()
                received += thread.received
            }
            return received
        }
    }

    companion object {
        private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
            var closed = false
            try {
                return block(this)
            } catch (e: Exception) {
                closed = true
                try {
                    close()
                } catch (closeException: Exception) {
                    //e.addSuppressed(closeException)
                }
                throw e
            } finally {
                if (!closed) {
                    close()
                }
            }
        }
    }
}