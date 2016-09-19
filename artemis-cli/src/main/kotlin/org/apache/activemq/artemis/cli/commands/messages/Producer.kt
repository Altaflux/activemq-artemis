package org.apache.activemq.artemis.cli.commands.messages

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.cli.commands.util.ProducerThread
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory
import org.apache.activemq.artemis.jms.client.ActiveMQDestination
import javax.jms.Session

@Command(name = "producer", description = "It will send messages to an instance")
class Producer : DestAbstract() {

    @Option(name = arrayOf("--non-persistent"), description = "It will send messages non persistently")
    var nonpersistent = false

    @Option(name = arrayOf("--message-size"), description = "Size of each byteMessage (The producer will use byte message on this case)")
    var messageSize = 0

    @Option(name = arrayOf("--text-size"), description = "Size of each textNessage (The producer will use text message on this case)")
    var textMessageSize: Int = 0

    @Option(name = arrayOf("--msgttl"), description = "TTL for each message")
    var msgTTL = 0L

    @Option(name = arrayOf("--group"), description = "Message Group to be used")
    var msgGroupID: String? = null


    override fun execute(context: ActionContext): Any? {
        super.execute(context)

        val factory = ActiveMQConnectionFactory(brokerURL, user, password)
        val dest = ActiveMQDestination.createDestination(this.destination, ActiveMQDestination.QUEUE_TYPE)

        factory.createConnection().use { connection ->
            val threadsArray = arrayOfNulls<ProducerThread>(threads)
            for (i in 0..threads -1) {

                val session = if (txBatchSize > 0) connection.createSession(true, Session.SESSION_TRANSACTED) else connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
                threadsArray[i] = ProducerThread(session, dest, i).apply {
                    this.verbose = verbose
                    this.persistent = !this@Producer.nonpersistent
                    this.messageSize = this@Producer.messageSize
                    this.textMessageSize = this@Producer.textMessageSize
                    this.msgTTL = this@Producer.msgTTL
                    this.msgGroupID = this@Producer.msgGroupID
                    this.transactionBatchSize = this@Producer.txBatchSize
                    this.messageCount = this@Producer.messageCount
                }
            }
            for (thread in threadsArray) {
                thread!!.start()
            }
            connection.start()
            var messagesProduced = 0
            for (thread in threadsArray) {
                thread!!.join()
                messagesProduced += thread.sentCount.get()
            }
            return messagesProduced
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
                  //  e.addSuppressed(closeException)
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