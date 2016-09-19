package org.apache.activemq.artemis.cli.commands.util

import org.apache.activemq.artemis.use
import java.util.*
import java.util.concurrent.CountDownLatch
import javax.jms.*
import javax.jms.Queue


class ConsumerThread(val session: Session, val destination: Destination, threadNr: Int)
: Thread("Consumer ${destination.toString()}, thread=$threadNr") {

    var messageCount = 1000
    var receiveTimeOut = 3000

    internal var durable: Boolean = false
    internal var breakOnNull = true
    internal var sleep: Int = 0
    internal var batchSize: Int = 0
    internal var verbose: Boolean = false
    internal var browse: Boolean = false
    internal var filter: String? = null
    internal var received = 0
    internal var transactions = 0
    internal var running = false
    internal var finished: CountDownLatch? = null
    internal var bytesAsText: Boolean = false

    override fun run(){
        if (browse) {
            browse()
        }
        else {
            consume()
        }
    }

    fun browse() {
        running = true

        val threadName = Thread.currentThread().name
        println("$threadName wait until $messageCount messages are consumed")
        val consumer = if (filter != null) session.createBrowser(destination as Queue, filter) else session.createBrowser(destination as Queue)

        @Suppress("UNCHECKED_CAST")
        val enumBrowse = consumer.enumeration as Enumeration<Message>

        try {
            consumer.use { consumer ->
                while (enumBrowse.hasMoreElements()) {
                    val msg = enumBrowse.nextElement()
                    if (msg != null) {
                        println(threadName + " Received " + if (msg is TextMessage) msg.text else msg.jmsMessageID)
                        if (verbose) {
                            println("..." + msg)
                        }
                        if (bytesAsText && msg is BytesMessage) {
                            val length = msg.bodyLength
                            val bytes = ByteArray(length.toInt())
                            msg.readBytes(bytes)
                            println("Message:" + msg)
                        }
                        received++
                        if (received >= messageCount) {
                            break
                        }
                    } else {
                        break
                    }
                    if (sleep > 0) {
                        Thread.sleep(sleep.toLong())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            finished?.countDown()
        }
    }

    fun consume() {
        running = true
        val threadName = Thread.currentThread().name
        println("$threadName wait until $messageCount messages are consumed")

        try {
            val consumer = if (durable && destination is Topic) {
                if (filter != null) {
                    session.createDurableSubscriber(destination, name, filter, false)
                } else {
                    session.createDurableSubscriber(destination, name)
                }
            } else {
                if (filter != null) {
                    session.createConsumer(destination, filter)
                } else {
                    session.createConsumer(destination)
                }

            }
            consumer.use { consumer ->
                while (running && received < messageCount) {
                    val msg = consumer.receive(receiveTimeOut.toLong())
                    if (msg != null) {
                        println(threadName + " Received " + if (msg is TextMessage) msg.text else msg.jmsMessageID)
                        if (verbose) {
                            println("..." + msg)
                        }
                        if (bytesAsText && msg is BytesMessage) {
                            val length = msg.bodyLength
                            val bytes = ByteArray(length.toInt())
                            msg.readBytes(bytes)
                            println("Message:" + msg)
                        }
                        received++
                    } else {
                        if (breakOnNull) {
                            break
                        }
                    }

                    if (session.transacted) run {
                        if (batchSize > 0 && received > 0 && received % batchSize == 0) {
                            println(threadName + " Committing transaction: " + transactions++)
                            session.commit()
                        }
                    }
                    else if (session.acknowledgeMode == Session.CLIENT_ACKNOWLEDGE) {
                        if (batchSize > 0 && received > 0 && received % batchSize == 0) {
                            println("Acknowledging last $batchSize messages; messages so far = $received")
                            msg?.acknowledge()
                        }
                    }
                    if (sleep > 0) {
                        Thread.sleep(sleep.toLong())
                    }
                }
                try {
                    session.commit()
                } catch (ignored: Throwable) {
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            finished?.countDown()
        }
    }

}