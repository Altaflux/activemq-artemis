package org.apache.activemq.artemis.cli.commands.util

import org.apache.activemq.artemis.utils.ReusableLatch
import use
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringWriter
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.jms.*


open class ProducerThread(val session: Session, val destination: Destination, threadNr: Int) :
        Thread("Producer ${destination.toString()}, thread=$threadNr") {


    internal var verbose: Boolean = false
    internal var messageCount = 1000
    internal var runIndefinitely = false
    internal var sleep = 0
    internal var persistent = true
    internal var messageSize = 0
    internal var textMessageSize: Int = 0
    internal var msgTTL = 0L
    internal var msgGroupID: String? = null
    internal var transactionBatchSize: Int = 0

    internal var transactions = 0
    internal val sentCount = AtomicInteger(0)
    internal var message: String? = null
    internal var messageText: String? = null
    internal var payloadUrl: String? = null
    internal var payload: ByteArray? = null
    internal var running = false
    internal val finished = ReusableLatch(1)
    internal val paused = ReusableLatch(0)


    override fun run() {

        val threadName = Thread.currentThread().name

        try {
            session.createProducer(destination).use { producer ->
                producer.deliveryMode = if (persistent) DeliveryMode.PERSISTENT else DeliveryMode.NON_PERSISTENT
                producer.timeToLive = msgTTL
                initPayLoad()
                running = true

                println(threadName + " Started to calculate elapsed time ...\n")
                val tStart = System.currentTimeMillis()

                if (runIndefinitely) {
                    while (running) {
                        paused.await()
                        sendMessage(producer, threadName)
                        sentCount.incrementAndGet()
                    }
                } else {
                    sentCount.set(0)
                    while (sentCount.get() < messageCount && running) {
                        paused.await()
                        sendMessage(producer, threadName)
                        sentCount.incrementAndGet()
                    }
                }

                try {
                    session.commit()
                } catch (ignored: Throwable) {
                }

                println("$threadName Produced: $sentCount messages")
                val tEnd = System.currentTimeMillis()
                val elapsed = (tEnd - tStart) / 1000
                println("$threadName Elapsed time in second : $elapsed s")
                println(threadName + " Elapsed time in milli second : " + (tEnd - tStart) + " milli seconds")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            finished.countDown()
        }

    }

    @Throws(Exception::class)
    private fun sendMessage(producer: MessageProducer, threadName: String) {
        val message = createMessage(sentCount.get(), threadName)
        producer.send(message)
        if (verbose) {
            println(threadName + " Sent: " + if (message is TextMessage) message.text else message.jmsMessageID)
        }

        if (transactionBatchSize > 0 && sentCount.get() > 0 && sentCount.get() % transactionBatchSize == 0) {
            println(threadName + " Committing transaction: " + transactions++)
            session.commit()
        }

        if (sleep > 0) {
            Thread.sleep(sleep.toLong())
        }
    }

    private fun initPayLoad() {
        if (messageSize > 0) {
            payload = ByteArray(messageSize)
            payload?.let { payload ->
                for (i in payload.indices) {
                    payload[i] = '.'.toByte()
                }
            }
        }
    }

    @Throws(Exception::class)
    open protected fun createMessage(i: Int, threadName: String): Message {
        val answer: Message
        if (payload != null) {
            answer = session.createBytesMessage()
            (answer as BytesMessage).writeBytes(payload)
        } else {
            if (textMessageSize > 0) {
                messageText = messageText ?: readInputStream(javaClass.getResourceAsStream("demo.txt"), textMessageSize, i)

            } else if (payloadUrl != null) {
                messageText = readInputStream(URL(payloadUrl).openStream(), -1, i)
            } else if (message != null) {
                messageText = message
            } else {
                messageText = createDefaultMessage(i)
            }
            answer = session.createTextMessage(messageText)
        }
        if (msgGroupID.isNullOrEmpty()) {
            answer.setStringProperty("JMSXGroupID", msgGroupID!!)
        }

        answer.setIntProperty("count", i)
        answer.setStringProperty("ThreadSent", threadName)
        return answer
    }

    @Throws(IOException::class)
    private fun readInputStream(`is`: InputStream, size: Int = 1024, messageNumber: Int): String {
        try {
            InputStreamReader(`is`).use { reader ->
                val buffer = StringWriter()
                reader.copyTo(buffer, size)
                return buffer.toString()
            }
        } catch (ioe: IOException) {
            return createDefaultMessage(messageNumber)
        }
    }

    private fun createDefaultMessage(messageNumber: Int): String = "test message: $messageNumber"
}