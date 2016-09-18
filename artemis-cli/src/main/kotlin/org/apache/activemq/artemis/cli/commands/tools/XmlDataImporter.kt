package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.api.core.Message
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.TransportConfiguration
import org.apache.activemq.artemis.api.core.client.ActiveMQClient
import org.apache.activemq.artemis.api.core.client.ClientMessage
import org.apache.activemq.artemis.api.core.client.ClientRequestor
import org.apache.activemq.artemis.api.core.client.ClientSession
import org.apache.activemq.artemis.api.core.management.ManagementHelper
import org.apache.activemq.artemis.api.core.management.ResourceNames
import org.apache.activemq.artemis.cli.commands.ActionAbstract
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.core.message.impl.MessageImpl
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants
import org.apache.activemq.artemis.core.server.ActiveMQServerLogger
import org.apache.activemq.artemis.utils.Base64
import org.apache.activemq.artemis.utils.UUIDGenerator
import java.io.*
import java.nio.ByteBuffer
import java.util.*
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Read XML output from <code>org.apache.activemq.artemis.core.persistence.impl.journal.XmlDataExporter</code>, create a core session, and
 * send the messages to a running instance of ActiveMQ Artemis.  It uses the StAX <code>javax.xml.stream.XMLStreamReader</code>
 * for speed and simplicity.
 */
@Command(name = "imp", description = "Import all message-data using an XML that could be interpreted by any system.")
class XmlDataImporter : ActionAbstract() {

    private lateinit var reader: XMLStreamReader

    lateinit var managementSession: ClientSession

    var localSession = false

    private val addressMap = mutableMapOf<String, String>()
    private val queueIDs = mutableMapOf<String, Long>()

    var tempFileName = ""

    private lateinit var session: ClientSession

    @Option(name = arrayOf("--host"), description = "The host used to import the data (default localhost)")
    var host = "localhost"

    @Option(name = arrayOf("--port"), description = "The port used to import the data (default 61616)")
    var port = 61616

    @Option(name = arrayOf("--transaction"), description = "If this is set to true you will need a whole transaction to commit at the end. (default false)")
    var transactional: Boolean = false

    @Option(name = arrayOf("--user"), description = "User name used to import the data. (default null)")
    var user: String? = null

    @Option(name = arrayOf("--password"), description = "User name used to import the data. (default null)")
    var password: String? = null

    @Option(name = arrayOf("--input"), description = "The input file name (default=exp.dmp)", required = true)
    var input = "exp.dmp"


    @Throws(Exception::class)
    override fun execute(context: ActionContext): Any? {
        process(input, host, port, transactional)
        return null
    }

    @Throws(Exception::class)
    fun process(inputFile: String, host: String, port: Int, transactional: Boolean) {
        this.process(FileInputStream(inputFile), host, port, transactional)
    }


    /**
     * This is the normal constructor for programmatic access to the
     * `org.apache.activemq.artemis.core.persistence.impl.journal.XmlDataImporter` if the session passed
     * in uses auto-commit for sends.
     *
     * If the session needs to be transactional then use the constructor which takes 2 sessions.

     * @param inputStream the stream from which to read the XML for import
     * *
     * @param session     used for sending messages, must use auto-commit for sends
     * *
     * @throws Exception
     */
    @Throws(Exception::class)
    fun process(inputStream: InputStream, session: ClientSession) {
        this.process(inputStream, session, null)
    }

    /**
     * This is the constructor to use if you wish to import all messages transactionally.
     * <br>
     * Pass in a session which doesn't use auto-commit for sends, and one that does (for management
     * operations necessary during import).
     *
     * @param inputStream       the stream from which to read the XML for import
     * @param session           used for sending messages, doesn't need to auto-commit sends
     * @param managementSession used for management queries, must use auto-commit for sends
     */
    @Throws(Exception::class)
    fun process(inputStream: InputStream,
                session: ClientSession,
                managementSession: ClientSession?) {
        reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream)
        this.session = session
        if (managementSession != null) {
            this.managementSession = managementSession
        } else {
            this.managementSession = session
        }
        localSession = false

        processXml()

    }

    @Throws(Exception::class)
    fun process(inputStream: InputStream, host: String, port: Int, transactional: Boolean) {
        reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream)
        val connectionParams = HashMap<String, Any>()
        connectionParams.put(TransportConstants.HOST_PROP_NAME, host)
        connectionParams.put(TransportConstants.PORT_PROP_NAME, Integer.toString(port))
        val serverLocator = ActiveMQClient.createServerLocatorWithoutHA(TransportConfiguration(NettyConnectorFactory::class.java.name, connectionParams))
        val sf = serverLocator.createSessionFactory()

        if (user != null || password != null) {
            session = sf.createSession(user, password, false, !transactional, true, false, 0)
            managementSession = sf.createSession(user, password, false, true, true, false, 0)
        } else {
            session = sf.createSession(false, !transactional, true)
            managementSession = sf.createSession(false, true, true)
        }
        localSession = true

        processXml()
    }


    @Throws(Exception::class)
    private fun processXml() {
        try {
            while (reader.hasNext()) {
                ActiveMQServerLogger.LOGGER.debug("EVENT:[" + reader.location.lineNumber + "][" + reader.location.columnNumber + "] ")
                if (reader.eventType == XMLStreamConstants.START_ELEMENT) {
                    if (XmlDataConstants.BINDINGS_CHILD == reader.localName) {
                        bindQueue()
                    } else if (XmlDataConstants.MESSAGES_CHILD == reader.localName) {
                        processMessage()
                    } else if (XmlDataConstants.JMS_CONNECTION_FACTORIES == reader.localName) {
                        createJmsConnectionFactories()
                    } else if (XmlDataConstants.JMS_DESTINATIONS == reader.localName) {
                        createJmsDestinations()
                    }
                }
                reader.next()
            }

            if (!session.isAutoCommitSends) {
                session.commit()
            }
        } finally {
            // if the session was created in our constructor then close it (otherwise the caller will close it)
            if (localSession) {
                session.close()
                managementSession.close()
            }
        }
    }

    @Throws(Exception::class)
    private fun processMessage() {
        var type: Byte? = 0
        var priority: Byte? = 0
        var expiration: Long? = 0L
        var timestamp: Long? = 0L
        var userId: org.apache.activemq.artemis.utils.UUID? = null
        val queues = ArrayList<String>()

        // get message's attributes
        for (i in 0..reader.attributeCount - 1) {
            val attributeName = reader.getAttributeLocalName(i)
            when (attributeName) {
                XmlDataConstants.MESSAGE_TYPE -> type = getMessageType(reader.getAttributeValue(i))
                XmlDataConstants.MESSAGE_PRIORITY -> priority = java.lang.Byte.parseByte(reader.getAttributeValue(i))
                XmlDataConstants.MESSAGE_EXPIRATION -> expiration = java.lang.Long.parseLong(reader.getAttributeValue(i))
                XmlDataConstants.MESSAGE_TIMESTAMP -> timestamp = java.lang.Long.parseLong(reader.getAttributeValue(i))
                XmlDataConstants.MESSAGE_USER_ID -> userId = UUIDGenerator.getInstance().generateUUID()
            }
        }

        val message = session.createMessage(type!!, true, expiration!!, timestamp!!, priority!!)
        message.userID = userId

        var endLoop = false

        // loop through the XML and gather up all the message's data (i.e. body, properties, queues, etc.)
        while (reader.hasNext()) {
            val eventType = reader.eventType
            when (eventType) {
                XMLStreamConstants.START_ELEMENT -> if (XmlDataConstants.MESSAGE_BODY == reader.localName) {
                    processMessageBody(message)
                } else if (XmlDataConstants.PROPERTIES_CHILD == reader.localName) {
                    processMessageProperties(message)
                } else if (XmlDataConstants.QUEUES_CHILD == reader.localName) {
                    processMessageQueues(queues)
                }
                XMLStreamConstants.END_ELEMENT -> if (XmlDataConstants.MESSAGES_CHILD == reader.localName) {
                    endLoop = true
                }
            }
            if (endLoop) {
                break
            }
            reader.next()
        }

        sendMessage(queues, message)
    }

    private fun getMessageType(value: String): Byte? {
        var type: Byte? = Message.DEFAULT_TYPE
        when (value) {
            XmlDataConstants.DEFAULT_TYPE_PRETTY -> type = Message.DEFAULT_TYPE
            XmlDataConstants.BYTES_TYPE_PRETTY -> type = Message.BYTES_TYPE
            XmlDataConstants.MAP_TYPE_PRETTY -> type = Message.MAP_TYPE
            XmlDataConstants.OBJECT_TYPE_PRETTY -> type = Message.OBJECT_TYPE
            XmlDataConstants.STREAM_TYPE_PRETTY -> type = Message.STREAM_TYPE
            XmlDataConstants.TEXT_TYPE_PRETTY -> type = Message.TEXT_TYPE
        }
        return type
    }

    @Throws(Exception::class)
    private fun sendMessage(queues: ArrayList<String>, message: Message) {
        val logMessage = StringBuilder()
        val destination = addressMap[queues[0]]

        logMessage.append("Sending ").append(message).append(" to address: ").append(destination).append("; routed to queues: ")
        val buffer = ByteBuffer.allocate(queues.size * 8)

        for (queue in queues) {
            val queueID: Long

            if (queueIDs.containsKey(queue)) {
                queueID = queueIDs[queue]!!
            } else {
                // Get the ID of the queues involved so the message can be routed properly.  This is done because we cannot
                // send directly to a queue, we have to send to an address instead but not all the queues related to the
                // address may need the message
                val requestor = ClientRequestor(managementSession, "jms.queue.activemq.management")
                val managementMessage = managementSession.createMessage(false)
                ManagementHelper.putAttribute(managementMessage, "core.queue." + queue, "ID")
                managementSession.start()
                ActiveMQServerLogger.LOGGER.debug("Requesting ID for: " + queue)
                val reply = requestor.request(managementMessage)
                val idObject = ManagementHelper.getResult(reply) as Number
                queueID = idObject.toLong()
                requestor.close()
                ActiveMQServerLogger.LOGGER.debug("ID for $queue is: $queueID")
                queueIDs.put(queue, queueID)  // store it so we don't have to look it up every time
            }

            logMessage.append(queue).append(", ")
            buffer.putLong(queueID)
        }

        logMessage.delete(logMessage.length - 2, logMessage.length) // take off the trailing comma
        ActiveMQServerLogger.LOGGER.debug(logMessage)

        message.putBytesProperty(MessageImpl.HDR_ROUTE_TO_IDS, buffer.array())
        session.createProducer(destination).use { producer -> producer.send(message) }

        if (tempFileName.length > 0) {
            val tempFile = File(tempFileName)
            if (!tempFile.delete()) {
                ActiveMQServerLogger.LOGGER.couldNotDeleteTempFile(tempFileName)
            }
            tempFileName = ""
        }
    }


    private fun processMessageQueues(queues: ArrayList<String>) {
        for (i in 0..reader.attributeCount - 1) {
            if (XmlDataConstants.QUEUE_NAME == reader.getAttributeLocalName(i)) {
                queues.add(reader.getAttributeValue(i))
            }
        }
    }

    private fun processMessageProperties(message: Message) {
        var key = ""
        var value = ""
        var propertyType = ""
        var realStringValue: String? = null
        var realSimpleStringValue: SimpleString? = null

        for (i in 0..reader.attributeCount - 1) {
            val attributeName = reader.getAttributeLocalName(i)
            when (attributeName) {
                XmlDataConstants.PROPERTY_NAME -> key = reader.getAttributeValue(i)
                XmlDataConstants.PROPERTY_VALUE -> value = reader.getAttributeValue(i)
                XmlDataConstants.PROPERTY_TYPE -> propertyType = reader.getAttributeValue(i)
            }
        }

        when (propertyType) {
            XmlDataConstants.PROPERTY_TYPE_SHORT -> message.putShortProperty(key, java.lang.Short.parseShort(value))
            XmlDataConstants.PROPERTY_TYPE_BOOLEAN -> message.putBooleanProperty(key, java.lang.Boolean.parseBoolean(value))
            XmlDataConstants.PROPERTY_TYPE_BYTE -> message.putByteProperty(key, java.lang.Byte.parseByte(value))
            XmlDataConstants.PROPERTY_TYPE_BYTES -> message.putBytesProperty(key, decode(value))
            XmlDataConstants.PROPERTY_TYPE_DOUBLE -> message.putDoubleProperty(key, java.lang.Double.parseDouble(value))
            XmlDataConstants.PROPERTY_TYPE_FLOAT -> message.putFloatProperty(key, java.lang.Float.parseFloat(value))
            XmlDataConstants.PROPERTY_TYPE_INTEGER -> message.putIntProperty(key, Integer.parseInt(value))
            XmlDataConstants.PROPERTY_TYPE_LONG -> message.putLongProperty(key, java.lang.Long.parseLong(value))
            XmlDataConstants.PROPERTY_TYPE_SIMPLE_STRING -> {
                if (value != XmlDataConstants.NULL) {
                    realSimpleStringValue = SimpleString(value)
                }
                message.putStringProperty(SimpleString(key), realSimpleStringValue)
            }
            XmlDataConstants.PROPERTY_TYPE_STRING -> {
                if (value != XmlDataConstants.NULL) {
                    realStringValue = value
                }
                message.putStringProperty(key, realStringValue)
            }
        }
    }

    @Throws(XMLStreamException::class, IOException::class)
    private fun processMessageBody(message: Message) {
        var isLarge = false

        for (i in 0..reader.attributeCount - 1) {
            val attributeName = reader.getAttributeLocalName(i)
            if (XmlDataConstants.MESSAGE_IS_LARGE == attributeName) {
                isLarge = java.lang.Boolean.parseBoolean(reader.getAttributeValue(i))
            }
        }
        reader.next()
        if (isLarge) {
            tempFileName = UUID.randomUUID().toString() + ".tmp"
            ActiveMQServerLogger.LOGGER.debug("Creating temp file $tempFileName for large message.")
            FileOutputStream(tempFileName).use({ out ->
                while (reader.hasNext()) {
                    if (reader.eventType == XMLStreamConstants.END_ELEMENT) {
                        break
                    } else {
                        val characters = String(reader.textCharacters, reader.textStart, reader.textLength)
                        val trimmedCharacters = characters.trim { it <= ' ' }
                        if (trimmedCharacters.length > 0) { // this will skip "indentation" characters
                            val data = decode(trimmedCharacters)
                            out.write(data)
                        }
                    }
                    reader.next()
                }
            })
            val fileInputStream = FileInputStream(tempFileName)
            val bufferedInput = BufferedInputStream(fileInputStream)
            (message as ClientMessage).setBodyInputStream(bufferedInput)
        } else {
            reader.next() // step past the "indentation" characters to get to the CDATA with the message body
            val characters = String(reader.textCharacters, reader.textStart, reader.textLength)
            message.bodyBuffer.writeBytes(decode(characters.trim { it <= ' ' }))
        }
    }


    @Throws(Exception::class)
    private fun bindQueue() {
        var queueName = ""
        var address = ""
        var filter = ""

        for (i in 0..reader.attributeCount - 1) {
            val attributeName = reader.getAttributeLocalName(i)
            when (attributeName) {
                XmlDataConstants.BINDING_ADDRESS -> address = reader.getAttributeValue(i)
                XmlDataConstants.BINDING_QUEUE_NAME -> queueName = reader.getAttributeValue(i)
                XmlDataConstants.BINDING_FILTER_STRING -> filter = reader.getAttributeValue(i)
            }
        }

        val queueQuery = session.queueQuery(SimpleString(queueName))

        if (!queueQuery.isExists) {
            session.createQueue(address, queueName, filter, true)
            ActiveMQServerLogger.LOGGER.debug("Binding queue(name=$queueName, address=$address, filter=$filter)")
        } else {
            ActiveMQServerLogger.LOGGER.debug("Binding $queueName already exists so won't re-bind.")
        }

        addressMap.put(queueName, address)
    }

    @Throws(Exception::class)
    private fun createJmsConnectionFactories() {
        var endLoop = false

        while (reader.hasNext()) {
            val eventType = reader.eventType
            when (eventType) {
                XMLStreamConstants.START_ELEMENT -> if (XmlDataConstants.JMS_CONNECTION_FACTORY == reader.localName) {
                    createJmsConnectionFactory()
                }
                XMLStreamConstants.END_ELEMENT -> if (XmlDataConstants.JMS_CONNECTION_FACTORIES == reader.localName) {
                    endLoop = true
                }
            }
            if (endLoop) {
                break
            }
            reader.next()
        }
    }

    @Throws(Exception::class)
    private fun createJmsDestinations() {
        var endLoop = false

        while (reader.hasNext()) {
            val eventType = reader.eventType
            when (eventType) {
                XMLStreamConstants.START_ELEMENT -> if (XmlDataConstants.JMS_DESTINATION == reader.localName) {
                    createJmsDestination()
                }
                XMLStreamConstants.END_ELEMENT -> if (XmlDataConstants.JMS_DESTINATIONS == reader.localName) {
                    endLoop = true
                }
            }
            if (endLoop) {
                break
            }
            reader.next()
        }
    }

    @Throws(Exception::class)
    private fun createJmsConnectionFactory() {
        var name = ""
        var callFailoverTimeout = ""
        var callTimeout = ""
        var clientFailureCheckPeriod = ""
        var clientId = ""
        var confirmationWindowSize = ""
        var connectionTtl = ""
        var connectors = ""
        var consumerMaxRate = ""
        var consumerWindowSize = ""
        var discoveryGroupName = ""
        var dupsOkBatchSize = ""
        var groupId = ""
        var loadBalancingPolicyClassName = ""
        var maxRetryInterval = ""
        var minLargeMessageSize = ""
        var producerMaxRate = ""
        var producerWindowSize = ""
        var reconnectAttempts = ""
        var retryInterval = ""
        var retryIntervalMultiplier = ""
        var scheduledThreadMaxPoolSize = ""
        var threadMaxPoolSize = ""
        var transactionBatchSize = ""
        var type = ""
        var entries = ""
        var autoGroup = ""
        var blockOnAcknowledge = ""
        var blockOnDurableSend = ""
        var blockOnNonDurableSend = ""
        var cacheLargeMessagesClient = ""
        var compressLargeMessages = ""
        var failoverOnInitialConnection = ""
        var ha = ""
        var preacknowledge = ""
        var useGlobalPools = ""

        var endLoop = false

        while (reader.hasNext()) {
            val eventType = reader.eventType
            when (eventType) {
                XMLStreamConstants.START_ELEMENT -> if (XmlDataConstants.JMS_CONNECTION_FACTORY_CALL_FAILOVER_TIMEOUT == reader.localName) {
                    callFailoverTimeout = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory callFailoverTimeout: " + callFailoverTimeout)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CALL_TIMEOUT == reader.localName) {
                    callTimeout = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory callTimeout: " + callTimeout)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CLIENT_FAILURE_CHECK_PERIOD == reader.localName) {
                    clientFailureCheckPeriod = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory clientFailureCheckPeriod: " + clientFailureCheckPeriod)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CLIENT_ID == reader.localName) {
                    clientId = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory clientId: " + clientId)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CONFIRMATION_WINDOW_SIZE == reader.localName) {
                    confirmationWindowSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory confirmationWindowSize: " + confirmationWindowSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CONNECTION_TTL == reader.localName) {
                    connectionTtl = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory connectionTtl: " + connectionTtl)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CONNECTOR == reader.localName) {
                    connectors = getConnectors()
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory getLocalName: " + connectors)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CONSUMER_MAX_RATE == reader.localName) {
                    consumerMaxRate = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory consumerMaxRate: " + consumerMaxRate)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CONSUMER_WINDOW_SIZE == reader.localName) {
                    consumerWindowSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory consumerWindowSize: " + consumerWindowSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_DISCOVERY_GROUP_NAME == reader.localName) {
                    discoveryGroupName = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory discoveryGroupName: " + discoveryGroupName)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_DUPS_OK_BATCH_SIZE == reader.localName) {
                    dupsOkBatchSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory dupsOkBatchSize: " + dupsOkBatchSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_GROUP_ID == reader.localName) {
                    groupId = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory groupId: " + groupId)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_LOAD_BALANCING_POLICY_CLASS_NAME == reader.localName) {
                    loadBalancingPolicyClassName = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory loadBalancingPolicyClassName: " + loadBalancingPolicyClassName)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_MAX_RETRY_INTERVAL == reader.localName) {
                    maxRetryInterval = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory maxRetryInterval: " + maxRetryInterval)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_MIN_LARGE_MESSAGE_SIZE == reader.localName) {
                    minLargeMessageSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory minLargeMessageSize: " + minLargeMessageSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_NAME == reader.localName) {
                    name = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory name: " + name)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_PRODUCER_MAX_RATE == reader.localName) {
                    producerMaxRate = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory producerMaxRate: " + producerMaxRate)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_PRODUCER_WINDOW_SIZE == reader.localName) {
                    producerWindowSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory producerWindowSize: " + producerWindowSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_RECONNECT_ATTEMPTS == reader.localName) {
                    reconnectAttempts = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory reconnectAttempts: " + reconnectAttempts)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_RETRY_INTERVAL == reader.localName) {
                    retryInterval = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory retryInterval: " + retryInterval)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_RETRY_INTERVAL_MULTIPLIER == reader.localName) {
                    retryIntervalMultiplier = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory retryIntervalMultiplier: " + retryIntervalMultiplier)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_SCHEDULED_THREAD_POOL_MAX_SIZE == reader.localName) {
                    scheduledThreadMaxPoolSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory scheduledThreadMaxPoolSize: " + scheduledThreadMaxPoolSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_THREAD_POOL_MAX_SIZE == reader.localName) {
                    threadMaxPoolSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory threadMaxPoolSize: " + threadMaxPoolSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_TRANSACTION_BATCH_SIZE == reader.localName) {
                    transactionBatchSize = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory transactionBatchSize: " + transactionBatchSize)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_TYPE == reader.localName) {
                    type = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory type: " + type)
                } else if (XmlDataConstants.JMS_JNDI_ENTRIES == reader.localName) {
                    entries = getEntries()
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory entries: " + entries)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_AUTO_GROUP == reader.localName) {
                    autoGroup = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory autoGroup: " + autoGroup)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_BLOCK_ON_ACKNOWLEDGE == reader.localName) {
                    blockOnAcknowledge = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory blockOnAcknowledge: " + blockOnAcknowledge)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_BLOCK_ON_DURABLE_SEND == reader.localName) {
                    blockOnDurableSend = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory blockOnDurableSend: " + blockOnDurableSend)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_BLOCK_ON_NON_DURABLE_SEND == reader.localName) {
                    blockOnNonDurableSend = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory blockOnNonDurableSend: " + blockOnNonDurableSend)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_CACHE_LARGE_MESSAGES_CLIENT == reader.localName) {
                    cacheLargeMessagesClient = reader.elementText
                    ActiveMQServerLogger.LOGGER.info("JMS connection factory $name cacheLargeMessagesClient: $cacheLargeMessagesClient")
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_COMPRESS_LARGE_MESSAGES == reader.localName) {
                    compressLargeMessages = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory compressLargeMessages: " + compressLargeMessages)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_FAILOVER_ON_INITIAL_CONNECTION == reader.localName) {
                    failoverOnInitialConnection = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory failoverOnInitialConnection: " + failoverOnInitialConnection)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_HA == reader.localName) {
                    ha = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory ha: " + ha)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_PREACKNOWLEDGE == reader.localName) {
                    preacknowledge = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory preacknowledge: " + preacknowledge)
                } else if (XmlDataConstants.JMS_CONNECTION_FACTORY_USE_GLOBAL_POOLS == reader.localName) {
                    useGlobalPools = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS connection factory useGlobalPools: " + useGlobalPools)
                }
                XMLStreamConstants.END_ELEMENT -> if (XmlDataConstants.JMS_CONNECTION_FACTORY == reader.localName) {
                    endLoop = true
                }
            }
            if (endLoop) {
                break
            }
            reader.next()
        }

        val requestor = ClientRequestor(managementSession, "jms.queue.activemq.management")
        val managementMessage = managementSession.createMessage(false)
        ManagementHelper.putOperationInvocation(managementMessage, ResourceNames.JMS_SERVER, "createConnectionFactory", name, java.lang.Boolean.parseBoolean(ha), discoveryGroupName.length > 0, Integer.parseInt(type), connectors, entries, clientId, java.lang.Long.parseLong(clientFailureCheckPeriod), java.lang.Long.parseLong(connectionTtl), java.lang.Long.parseLong(callTimeout), java.lang.Long.parseLong(callFailoverTimeout), Integer.parseInt(minLargeMessageSize), java.lang.Boolean.parseBoolean(compressLargeMessages), Integer.parseInt(consumerWindowSize), Integer.parseInt(consumerMaxRate), Integer.parseInt(confirmationWindowSize), Integer.parseInt(producerWindowSize), Integer.parseInt(producerMaxRate), java.lang.Boolean.parseBoolean(blockOnAcknowledge), java.lang.Boolean.parseBoolean(blockOnDurableSend), java.lang.Boolean.parseBoolean(blockOnNonDurableSend), java.lang.Boolean.parseBoolean(autoGroup), java.lang.Boolean.parseBoolean(preacknowledge), loadBalancingPolicyClassName, Integer.parseInt(transactionBatchSize), Integer.parseInt(dupsOkBatchSize), java.lang.Boolean.parseBoolean(useGlobalPools), Integer.parseInt(scheduledThreadMaxPoolSize), Integer.parseInt(threadMaxPoolSize), java.lang.Long.parseLong(retryInterval), java.lang.Double.parseDouble(retryIntervalMultiplier), java.lang.Long.parseLong(maxRetryInterval), Integer.parseInt(reconnectAttempts), java.lang.Boolean.parseBoolean(failoverOnInitialConnection), groupId)
        //Boolean.parseBoolean(cacheLargeMessagesClient));
        managementSession.start()
        val reply = requestor.request(managementMessage)
        if (ManagementHelper.hasOperationSucceeded(reply)) {
            ActiveMQServerLogger.LOGGER.debug("Created connection factory " + name)
        } else {
            ActiveMQServerLogger.LOGGER.error("Problem creating " + name)
        }

        requestor.close()
    }

    private fun createJmsDestination() {
        var name = ""
        var selector = ""
        var entries = ""
        var type = ""
        var endLoop = false

        while (reader.hasNext()) {
            val eventType = reader.eventType
            when (eventType) {
                XMLStreamConstants.START_ELEMENT -> if (XmlDataConstants.JMS_DESTINATION_NAME == reader.localName) {
                    name = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS destination name: " + name)
                } else if (XmlDataConstants.JMS_DESTINATION_SELECTOR == reader.localName) {
                    selector = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS destination selector: " + selector)
                } else if (XmlDataConstants.JMS_DESTINATION_TYPE == reader.localName) {
                    type = reader.elementText
                    ActiveMQServerLogger.LOGGER.debug("JMS destination type: " + type)
                } else if (XmlDataConstants.JMS_JNDI_ENTRIES == reader.localName) {
                    entries = getEntries()
                }
                XMLStreamConstants.END_ELEMENT -> if (XmlDataConstants.JMS_DESTINATION == reader.localName) {
                    endLoop = true
                }
            }
            if (endLoop) {
                break
            }
            reader.next()
        }

        val requestor = ClientRequestor(managementSession, "jms.queue.activemq.management")
        val managementMessage = managementSession.createMessage(false)
        if ("Queue" == type) {
            ManagementHelper.putOperationInvocation(managementMessage, ResourceNames.JMS_SERVER, "createQueue", name, entries, selector)
        } else if ("Topic" == type) {
            ManagementHelper.putOperationInvocation(managementMessage, ResourceNames.JMS_SERVER, "createTopic", name, entries)
        }
        managementSession.start()
        val reply = requestor.request(managementMessage)
        if (ManagementHelper.hasOperationSucceeded(reply)) {
            ActiveMQServerLogger.LOGGER.debug("Created " + type.toLowerCase() + " " + name)
        } else {
            ActiveMQServerLogger.LOGGER.error("Problem creating " + name)
        }

        requestor.close()
    }

    @Throws(Exception::class)
    private fun getEntries(): String {
        val entry = StringBuilder()
        var endLoop = false

        while (reader.hasNext()) {
            val eventType = reader.eventType
            when (eventType) {
                XMLStreamConstants.START_ELEMENT -> if (XmlDataConstants.JMS_JNDI_ENTRY == reader.localName) {
                    val elementText = reader.elementText
                    entry.append(elementText).append(", ")
                    ActiveMQServerLogger.LOGGER.debug("JMS admin object JNDI entry: " + entry.toString())
                }
                XMLStreamConstants.END_ELEMENT -> if (XmlDataConstants.JMS_JNDI_ENTRIES == reader.localName) {
                    endLoop = true
                }
            }
            if (endLoop) {
                break
            }
            reader.next()
        }

        return entry.delete(entry.length - 2, entry.length).toString()
    }

    private fun getConnectors(): String {
        val entry = StringBuilder()
        var endLoop = false
        while (reader.hasNext()) {
            val eventType = reader.eventType
            when (eventType) {
                XMLStreamConstants.START_ELEMENT -> if (XmlDataConstants.JMS_CONNECTION_FACTORY_CONNECTOR == reader.localName) {
                    entry.append(reader.elementText).append(", ")
                }
                XMLStreamConstants.END_ELEMENT -> if (XmlDataConstants.JMS_CONNECTION_FACTORY_CONNECTORS == reader.localName) {
                    endLoop = true
                }
            }
            if (endLoop) {
                break
            }
            reader.next()
        }

        return entry.delete(entry.length - 2, entry.length).toString()
    }

    companion object {
        private fun decode(data: String): ByteArray {
            return Base64.decode(data, Base64.DONT_BREAK_LINES or Base64.URL_SAFE)
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