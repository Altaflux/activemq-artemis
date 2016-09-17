package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Command
import org.apache.activemq.artemis.api.core.*
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.core.config.Configuration
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl
import org.apache.activemq.artemis.core.io.nio.NIOSequentialFileFactory
import org.apache.activemq.artemis.core.journal.PreparedTransactionInfo
import org.apache.activemq.artemis.core.journal.RecordInfo
import org.apache.activemq.artemis.core.journal.TransactionFailureCallback
import org.apache.activemq.artemis.core.journal.impl.JournalImpl
import org.apache.activemq.artemis.core.message.BodyEncoder
import org.apache.activemq.artemis.core.paging.cursor.PagePosition
import org.apache.activemq.artemis.core.paging.cursor.impl.PagePositionImpl
import org.apache.activemq.artemis.core.paging.impl.PageTransactionInfoImpl
import org.apache.activemq.artemis.core.paging.impl.PagingManagerImpl
import org.apache.activemq.artemis.core.paging.impl.PagingStoreFactoryNIO
import org.apache.activemq.artemis.core.persistence.impl.journal.AckDescribe
import org.apache.activemq.artemis.core.persistence.impl.journal.DescribeJournal
import org.apache.activemq.artemis.core.persistence.impl.journal.JournalRecordIds
import org.apache.activemq.artemis.core.persistence.impl.journal.JournalStorageManager
import org.apache.activemq.artemis.core.persistence.impl.journal.codec.CursorAckRecordEncoding
import org.apache.activemq.artemis.core.persistence.impl.journal.codec.PageUpdateTXEncoding
import org.apache.activemq.artemis.core.persistence.impl.journal.codec.PersistentQueueBindingEncoding
import org.apache.activemq.artemis.core.server.ActiveMQServerLogger
import org.apache.activemq.artemis.core.server.JournalType
import org.apache.activemq.artemis.core.server.LargeServerMessage
import org.apache.activemq.artemis.core.server.ServerMessage
import org.apache.activemq.artemis.core.settings.impl.AddressSettings
import org.apache.activemq.artemis.core.settings.impl.HierarchicalObjectRepository
import org.apache.activemq.artemis.jms.persistence.config.PersistedBindings
import org.apache.activemq.artemis.jms.persistence.config.PersistedConnectionFactory
import org.apache.activemq.artemis.jms.persistence.config.PersistedDestination
import org.apache.activemq.artemis.jms.persistence.config.PersistedType
import org.apache.activemq.artemis.jms.persistence.impl.journal.JMSJournalStorageManagerImpl
import org.apache.activemq.artemis.utils.ActiveMQThreadFactory
import org.apache.activemq.artemis.utils.Base64
import org.apache.activemq.artemis.utils.ExecutorFactory
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.*
import java.util.concurrent.ConcurrentHashMap

import java.util.concurrent.Executors
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamWriter

@Command(name = "exp", description = "Export all message-data using an XML that could be interpreted by any system.")
class XmlDataExporter : OptionalLocking() {

    private val LARGE_MESSAGE_CHUNK_SIZE = 1000L

    private lateinit var storageManager: JournalStorageManager
    private lateinit var config: Configuration
    private lateinit var xmlWriter: XMLStreamWriter

    // an inner map of message refs hashed by the queue ID to which they belong and then hashed by their record ID
    private val messageRefs = mutableMapOf<Long, MutableMap<Long, DescribeJournal.ReferenceDescribe>>()

    // map of all message records hashed by their record ID (which will match the record ID of the message refs)
    private val messages = mutableMapOf<Long, Message>()
    private val cursorRecords = mutableMapOf<Long, MutableSet<PagePosition>>()
    private val pgTXs = mutableSetOf<Long>()
    private val queueBindings = mutableMapOf<Long, PersistentQueueBindingEncoding>()
    private val jmsConnectionFactories = ConcurrentHashMap<String, PersistedConnectionFactory>()
    private val jmsDestinations = ConcurrentHashMap<Pair<PersistedType, String>, PersistedDestination>()
    private val jmsJNDI = ConcurrentHashMap<Pair<PersistedType, String>, PersistedBindings>()

    internal var messagesPrinted = 0L
    internal var bindingsPrinted = 0L


    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        try {
            process(context.out, getBinding(), getJournal(), getPaging(), largeMessages)
        } catch (e: Exception) {
            treatError(e, "data", "exp")
        }
        return null
    }

    @Throws(Exception::class)
    fun process(out: OutputStream,
                bindingsDir: String,
                journalDir: String,
                pagingDir: String,
                largeMessagesDir: String) {
        config = ConfigurationImpl().setBindingsDirectory(bindingsDir).setJournalDirectory(journalDir).setPagingDirectory(pagingDir).setLargeMessagesDirectory(largeMessagesDir).setJournalType(JournalType.NIO)
        val executor = Executors.newFixedThreadPool(1, ActiveMQThreadFactory.defaultThreadFactory())
        val executorFactory = ExecutorFactory { executor }

        storageManager = JournalStorageManager(config, executorFactory)
        val factory = XMLOutputFactory.newInstance()
        val rawXmlWriter = factory.createXMLStreamWriter(out, "UTF-8")
        val handler = PrettyPrintHandler(rawXmlWriter)
        xmlWriter = Proxy.newProxyInstance(XMLStreamWriter::class.java.classLoader, arrayOf<Class<*>>(XMLStreamWriter::class.java), handler) as XMLStreamWriter

        writeXMLData()
    }


    @Throws(Exception::class)
    private fun writeXMLData() {
        val start = System.currentTimeMillis()
        getBindings()
        getJmsBindings()
        processMessageJournal()
        printDataAsXML()
        ActiveMQServerLogger.LOGGER.debug("\n\nProcessing took: " + (System.currentTimeMillis() - start) + "ms")
        ActiveMQServerLogger.LOGGER.debug("Output $messagesPrinted messages and $bindingsPrinted bindings.")
    }

    private fun processMessageJournal() {
        val acks = mutableListOf<RecordInfo>()
        val records: MutableList<RecordInfo> = LinkedList()
        val preparedTransactions: MutableList<PreparedTransactionInfo> = LinkedList()

        val messageJournal = storageManager.messageJournal
        ActiveMQServerLogger.LOGGER.debug("Reading journal from " + config.journalDirectory)
        messageJournal.start()
        //transactionID: Long, records1 : List<RecordInfo>, List<RecordInfo> recordsToDelete
        val transactionFailureCallback = TransactionFailureCallback { transactionID, records1, recordsToDelete ->
            val message = StringBuilder()
            message.append("Encountered failed journal transaction: ").append(transactionID)
            for (i in records1.indices) {
                if (i == 0) {
                    message.append("; Records: ")
                }
                message.append(records1[i])
                if (i != records1.size - 1) {
                    message.append(", ")
                }
            }

            for (i in recordsToDelete.indices) {
                if (i == 0) {
                    message.append("; RecordsToDelete: ")
                }
                message.append(recordsToDelete[i])
                if (i != recordsToDelete.size - 1) {
                    message.append(", ")
                }
            }
            ActiveMQServerLogger.LOGGER.debug(message.toString())
        }

        (messageJournal as JournalImpl).load(records, preparedTransactions, transactionFailureCallback, false)
        preparedTransactions.clear()

        for (info in records) {
            val data = info.data
            val buff = ActiveMQBuffers.wrappedBuffer(data)
            val o = DescribeJournal.newObjectEncoding(info, storageManager)
            if (info.getUserRecordType() == JournalRecordIds.ADD_MESSAGE) {
                messages.put(info.id, (o as DescribeJournal.MessageDescribe).msg)
            } else if (info.getUserRecordType() == JournalRecordIds.ADD_LARGE_MESSAGE) {
                messages.put(info.id, (o as DescribeJournal.MessageDescribe).msg)
            } else if (info.getUserRecordType() == JournalRecordIds.ADD_REF) {
                val ref = o as DescribeJournal.ReferenceDescribe
                val map = messageRefs[info.id]
                if (map == null) {
                    val newMap = mutableMapOf(ref.refEncoding.queueID to ref)
                    messageRefs[info.id] = newMap
                } else {
                    map[ref.refEncoding.queueID] = ref
                }
            } else if (info.getUserRecordType() == JournalRecordIds.ACKNOWLEDGE_REF) {
                acks.add(info)
            } else if (info.userRecordType == JournalRecordIds.ACKNOWLEDGE_CURSOR) {
                val encoding = CursorAckRecordEncoding()
                encoding.decode(buff)

                var set: MutableSet<PagePosition>? = cursorRecords[encoding.queueID]

                if (set == null) {
                    set = HashSet<PagePosition>()
                    cursorRecords.put(encoding.queueID, set)
                }

                set.add(encoding.position)
            } else if (info.userRecordType == JournalRecordIds.PAGE_TRANSACTION) {
                if (info.isUpdate) {
                    val pageUpdate = PageUpdateTXEncoding()

                    pageUpdate.decode(buff)
                    pgTXs.add(pageUpdate.pageTX)
                } else {
                    val pageTransactionInfo = PageTransactionInfoImpl()

                    pageTransactionInfo.decode(buff)

                    pageTransactionInfo.recordID = info.id
                    pgTXs.add(pageTransactionInfo.transactionID)
                }
            }
        }

        messageJournal.stop()
        removeAcked(acks)
    }

    private fun removeAcked(acks: List<RecordInfo>) {
        for (info in acks) {
            val ack = DescribeJournal.newObjectEncoding(info, null) as AckDescribe
            messageRefs[info.id]?.let { referenceDescribeHashMap ->
                referenceDescribeHashMap.remove(ack.refEncoding.queueID)
                if (referenceDescribeHashMap.size == 0) {
                    messages.remove(info.id)
                    messageRefs.remove(info.id)
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun getJmsBindings() {
        val bindingsJMS = NIOSequentialFileFactory(config.bindingsLocation, 1)
        val jmsJournal = JournalImpl(1024 * 1024, 2, 2, config.journalCompactMinFiles, config.journalCompactPercentage, bindingsJMS, "activemq-jms", "jms", 1)
        jmsJournal.start()
        val data = mutableListOf<RecordInfo>()
        val list = mutableListOf<PreparedTransactionInfo>()

        ActiveMQServerLogger.LOGGER.debug("Reading jms bindings journal from " + config.bindingsDirectory)
        jmsJournal.load(data, list, null)

        for (record in data) {
            val id = record.id
            val buffer = ActiveMQBuffers.wrappedBuffer(record.data)
            val rec = record.getUserRecordType()
            if (rec == JMSJournalStorageManagerImpl.CF_RECORD) {
                val cf = PersistedConnectionFactory()
                cf.decode(buffer)
                cf.id = id
                ActiveMQServerLogger.LOGGER.info("Found JMS connection factory: " + cf.name)
                jmsConnectionFactories.put(cf.name, cf)
            } else if (rec == JMSJournalStorageManagerImpl.DESTINATION_RECORD) {
                val destination = PersistedDestination()
                destination.decode(buffer)
                destination.id = id
                ActiveMQServerLogger.LOGGER.info("Found JMS destination: " + destination.name)
                jmsDestinations.put(Pair(destination.type, destination.name), destination)
            } else if (rec == JMSJournalStorageManagerImpl.BINDING_RECORD) {
                val jndi = PersistedBindings()
                jndi.decode(buffer)
                jndi.id = id
                val key = Pair(jndi.type, jndi.name)
                val builder = StringBuilder()
                for (binding in jndi.bindings) {
                    builder.append(binding).append(" ")
                }
                ActiveMQServerLogger.LOGGER.info("Found JMS JNDI binding data for " + jndi.type + " " + jndi.name + ": " + builder.toString())
                jmsJNDI.put(key, jndi)
            } else {
                throw IllegalStateException("Invalid record type " + rec)
            }
        }
    }

    @Throws(Exception::class)
    private fun getBindings() {
        val records = LinkedList<RecordInfo>()
        val bindingsJournal = storageManager.bindingsJournal
        bindingsJournal.start()
        ActiveMQServerLogger.LOGGER.debug("Reading bindings journal from " + config.bindingsDirectory)
        (bindingsJournal as JournalImpl).load(records, null, null, false)

        for (info in records) {
            if (info.getUserRecordType() == JournalRecordIds.QUEUE_BINDING_RECORD) {
                val bindingEncoding = DescribeJournal.newObjectEncoding(info, null) as PersistentQueueBindingEncoding
                queueBindings.put(bindingEncoding.getId(), bindingEncoding)
            }
        }
        bindingsJournal.stop()
    }

    private fun printDataAsXML() {
        try {
            xmlWriter.writeStartDocument(XmlDataConstants.XML_VERSION)
            xmlWriter.writeStartElement(XmlDataConstants.DOCUMENT_PARENT)
            printBindingsAsXML()
            printJmsConnectionFactoriesAsXML()
            printJmsDestinationsAsXML()
            printAllMessagesAsXML()
            xmlWriter.writeEndElement() // end DOCUMENT_PARENT
            xmlWriter.writeEndDocument()
            xmlWriter.flush()
            xmlWriter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun printBindingsAsXML() {
        xmlWriter.writeStartElement(XmlDataConstants.BINDINGS_PARENT)
        for ((key) in queueBindings) {
            val bindingEncoding = queueBindings[key] ?: continue

            xmlWriter.writeEmptyElement(XmlDataConstants.BINDINGS_CHILD)
            xmlWriter.writeAttribute(XmlDataConstants.BINDING_ADDRESS, bindingEncoding.getAddress().toString())
            var filter = ""
            if (bindingEncoding.getFilterString() != null) {
                filter = bindingEncoding.getFilterString().toString()
            }
            xmlWriter.writeAttribute(XmlDataConstants.BINDING_FILTER_STRING, filter)
            xmlWriter.writeAttribute(XmlDataConstants.BINDING_QUEUE_NAME, bindingEncoding.queueName.toString())
            xmlWriter.writeAttribute(XmlDataConstants.BINDING_ID, java.lang.Long.toString(bindingEncoding.getId()))
            bindingsPrinted++
        }
        xmlWriter.writeEndElement() // end BINDINGS_PARENT
    }

    @Throws(XMLStreamException::class)
    private fun printAllMessagesAsXML() {
        xmlWriter.writeStartElement(XmlDataConstants.MESSAGES_PARENT)

        // Order here is important.  We must process the messages from the journal before we process those from the page
        // files in order to get the messages in the right order.
        for ((key, value) in messages) {
            printSingleMessageAsXML(value as ServerMessage, extractQueueNames(messageRefs[key]!!))
        }

        printPagedMessagesAsXML()

        xmlWriter.writeEndElement() // end "messages"
    }


    @Throws(XMLStreamException::class)
    private fun printJmsConnectionFactoriesAsXML() {
        xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORIES)
        for (jmsConnectionFactoryKey in jmsConnectionFactories.keys) {
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY)
            val jmsConnectionFactory = jmsConnectionFactories[jmsConnectionFactoryKey] ?: continue

            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_NAME)
            xmlWriter.writeCharacters(jmsConnectionFactory.name)
            xmlWriter.writeEndElement()
            val clientID = jmsConnectionFactory.config.clientID
            if (clientID != null) {
                xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CLIENT_ID)
                xmlWriter.writeCharacters(clientID)
                xmlWriter.writeEndElement()
            }

            val callFailoverTimeout = jmsConnectionFactory.config.callFailoverTimeout
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CALL_FAILOVER_TIMEOUT)
            xmlWriter.writeCharacters(java.lang.Long.toString(callFailoverTimeout))
            xmlWriter.writeEndElement()

            val callTimeout = jmsConnectionFactory.config.callTimeout
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CALL_TIMEOUT)
            xmlWriter.writeCharacters(java.lang.Long.toString(callTimeout))
            xmlWriter.writeEndElement()

            val clientFailureCheckPeriod = jmsConnectionFactory.config.clientFailureCheckPeriod
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CLIENT_FAILURE_CHECK_PERIOD)
            xmlWriter.writeCharacters(java.lang.Long.toString(clientFailureCheckPeriod))
            xmlWriter.writeEndElement()

            val confirmationWindowSize = jmsConnectionFactory.config.confirmationWindowSize
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CONFIRMATION_WINDOW_SIZE)
            xmlWriter.writeCharacters(Integer.toString(confirmationWindowSize))
            xmlWriter.writeEndElement()

            val connectionTTL = jmsConnectionFactory.config.connectionTTL
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CONNECTION_TTL)
            xmlWriter.writeCharacters(java.lang.Long.toString(connectionTTL))
            xmlWriter.writeEndElement()

            val consumerMaxRate = jmsConnectionFactory.config.consumerMaxRate.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CONSUMER_MAX_RATE)
            xmlWriter.writeCharacters(java.lang.Long.toString(consumerMaxRate))
            xmlWriter.writeEndElement()

            val consumerWindowSize = jmsConnectionFactory.config.consumerWindowSize.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CONSUMER_WINDOW_SIZE)
            xmlWriter.writeCharacters(java.lang.Long.toString(consumerWindowSize))
            xmlWriter.writeEndElement()

            val discoveryGroupName = jmsConnectionFactory.config.discoveryGroupName
            if (discoveryGroupName != null) {
                xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_DISCOVERY_GROUP_NAME)
                xmlWriter.writeCharacters(discoveryGroupName)
                xmlWriter.writeEndElement()
            }

            val dupsOKBatchSize = jmsConnectionFactory.config.dupsOKBatchSize
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_DUPS_OK_BATCH_SIZE)
            xmlWriter.writeCharacters(Integer.toString(dupsOKBatchSize))
            xmlWriter.writeEndElement()

            val factoryType = jmsConnectionFactory.config.factoryType
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_TYPE)
            xmlWriter.writeCharacters(Integer.toString(factoryType.intValue()))
            xmlWriter.writeEndElement()

            val groupID = jmsConnectionFactory.config.groupID
            if (groupID != null) {
                xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_GROUP_ID)
                xmlWriter.writeCharacters(groupID)
                xmlWriter.writeEndElement()
            }

            val loadBalancingPolicyClassName = jmsConnectionFactory.config.loadBalancingPolicyClassName
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_LOAD_BALANCING_POLICY_CLASS_NAME)
            xmlWriter.writeCharacters(loadBalancingPolicyClassName)
            xmlWriter.writeEndElement()

            val maxRetryInterval = jmsConnectionFactory.config.maxRetryInterval
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_MAX_RETRY_INTERVAL)
            xmlWriter.writeCharacters(java.lang.Long.toString(maxRetryInterval))
            xmlWriter.writeEndElement()

            val minLargeMessageSize = jmsConnectionFactory.config.minLargeMessageSize.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_MIN_LARGE_MESSAGE_SIZE)
            xmlWriter.writeCharacters(java.lang.Long.toString(minLargeMessageSize))
            xmlWriter.writeEndElement()

            val producerMaxRate = jmsConnectionFactory.config.producerMaxRate.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_PRODUCER_MAX_RATE)
            xmlWriter.writeCharacters(java.lang.Long.toString(producerMaxRate))
            xmlWriter.writeEndElement()

            val producerWindowSize = jmsConnectionFactory.config.producerWindowSize.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_PRODUCER_WINDOW_SIZE)
            xmlWriter.writeCharacters(java.lang.Long.toString(producerWindowSize))
            xmlWriter.writeEndElement()

            val reconnectAttempts = jmsConnectionFactory.config.reconnectAttempts.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_RECONNECT_ATTEMPTS)
            xmlWriter.writeCharacters(java.lang.Long.toString(reconnectAttempts))
            xmlWriter.writeEndElement()

            val retryInterval = jmsConnectionFactory.config.retryInterval
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_RETRY_INTERVAL)
            xmlWriter.writeCharacters(java.lang.Long.toString(retryInterval))
            xmlWriter.writeEndElement()

            val retryIntervalMultiplier = jmsConnectionFactory.config.retryIntervalMultiplier
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_RETRY_INTERVAL_MULTIPLIER)
            xmlWriter.writeCharacters(java.lang.Double.toString(retryIntervalMultiplier))
            xmlWriter.writeEndElement()

            val scheduledThreadPoolMaxSize = jmsConnectionFactory.config.scheduledThreadPoolMaxSize.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_SCHEDULED_THREAD_POOL_MAX_SIZE)
            xmlWriter.writeCharacters(java.lang.Long.toString(scheduledThreadPoolMaxSize))
            xmlWriter.writeEndElement()

            val threadPoolMaxSize = jmsConnectionFactory.config.threadPoolMaxSize.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_THREAD_POOL_MAX_SIZE)
            xmlWriter.writeCharacters(java.lang.Long.toString(threadPoolMaxSize))
            xmlWriter.writeEndElement()

            val transactionBatchSize = jmsConnectionFactory.config.transactionBatchSize.toLong()
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_TRANSACTION_BATCH_SIZE)
            xmlWriter.writeCharacters(java.lang.Long.toString(transactionBatchSize))
            xmlWriter.writeEndElement()

            val autoGroup = jmsConnectionFactory.config.isAutoGroup
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_AUTO_GROUP)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(autoGroup))
            xmlWriter.writeEndElement()

            val blockOnAcknowledge = jmsConnectionFactory.config.isBlockOnAcknowledge
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_BLOCK_ON_ACKNOWLEDGE)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(blockOnAcknowledge))
            xmlWriter.writeEndElement()

            val blockOnDurableSend = jmsConnectionFactory.config.isBlockOnDurableSend
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_BLOCK_ON_DURABLE_SEND)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(blockOnDurableSend))
            xmlWriter.writeEndElement()

            val blockOnNonDurableSend = jmsConnectionFactory.config.isBlockOnNonDurableSend
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_BLOCK_ON_NON_DURABLE_SEND)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(blockOnNonDurableSend))
            xmlWriter.writeEndElement()

            val cacheLargeMessagesClient = jmsConnectionFactory.config.isCacheLargeMessagesClient
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CACHE_LARGE_MESSAGES_CLIENT)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(cacheLargeMessagesClient))
            xmlWriter.writeEndElement()

            val compressLargeMessages = jmsConnectionFactory.config.isCompressLargeMessages
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_COMPRESS_LARGE_MESSAGES)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(compressLargeMessages))
            xmlWriter.writeEndElement()

            val failoverOnInitialConnection = jmsConnectionFactory.config.isFailoverOnInitialConnection
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_FAILOVER_ON_INITIAL_CONNECTION)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(failoverOnInitialConnection))
            xmlWriter.writeEndElement()

            val ha = jmsConnectionFactory.config.isHA
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_HA)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(ha))
            xmlWriter.writeEndElement()

            val preAcknowledge = jmsConnectionFactory.config.isPreAcknowledge
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_PREACKNOWLEDGE)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(preAcknowledge))
            xmlWriter.writeEndElement()

            val useGlobalPools = jmsConnectionFactory.config.isUseGlobalPools
            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_USE_GLOBAL_POOLS)
            xmlWriter.writeCharacters(java.lang.Boolean.toString(useGlobalPools))
            xmlWriter.writeEndElement()

            xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CONNECTORS)
            for (connector in jmsConnectionFactory.config.connectorNames) {
                xmlWriter.writeStartElement(XmlDataConstants.JMS_CONNECTION_FACTORY_CONNECTOR)
                xmlWriter.writeCharacters(connector)
                xmlWriter.writeEndElement()
            }
            xmlWriter.writeEndElement()

            xmlWriter.writeStartElement(XmlDataConstants.JMS_JNDI_ENTRIES)
            val jndi = jmsJNDI[Pair(PersistedType.ConnectionFactory, jmsConnectionFactory.name)]

            jndi?.let {
                for (jndiEntry in jndi.bindings) {
                    xmlWriter.writeStartElement(XmlDataConstants.JMS_JNDI_ENTRY)
                    xmlWriter.writeCharacters(jndiEntry)
                    xmlWriter.writeEndElement()
                }
            }

            xmlWriter.writeEndElement() // end jndi-entries
            xmlWriter.writeEndElement() // end JMS_CONNECTION_FACTORY
        }
        xmlWriter.writeEndElement()
    }


    private fun printPagedMessagesAsXML() {
        try {
            val scheduled = Executors.newScheduledThreadPool(1, ActiveMQThreadFactory.defaultThreadFactory())
            val executor = Executors.newFixedThreadPool(10, ActiveMQThreadFactory.defaultThreadFactory())
            val executorFactory = ExecutorFactory { executor }
            val pageStoreFactory = PagingStoreFactoryNIO(storageManager, config.pagingLocation, 1000L, scheduled, executorFactory, true, null)
            val addressSettingsRepository = HierarchicalObjectRepository<AddressSettings>()
            addressSettingsRepository.setDefault(AddressSettings())
            val manager = PagingManagerImpl(pageStoreFactory, addressSettingsRepository)

            manager.start()
            val stores = manager.storeNames

            for (store in stores) {
                val pageStore = manager.getPageStore(store)
                pageStore?.let { pageStore ->
                    val folder = pageStore.folder
                    ActiveMQServerLogger.LOGGER.debug("Reading page store $store folder = $folder")
                    var pageId = pageStore.firstPage.toInt()

                    kotlin.repeat(pageStore.numberOfPages) {
                        ActiveMQServerLogger.LOGGER.debug("Reading page " + pageId)
                        val page = pageStore.createPage(pageId)
                        page.open()
                        val messages = page.read(storageManager)
                        page.close()
                        var messageId = 0

                        for (message in messages) {
                            message.initMessage(storageManager)
                            val queueIDs = message.queueIDs
                            val queueNames = mutableListOf<String>()
                            for (queueID in queueIDs) {
                                val posCheck = PagePositionImpl(pageId.toLong(), messageId)
                                var acked = false
                                val positions = cursorRecords[queueID]
                                if (positions != null) {
                                    acked = positions.contains(posCheck)
                                }
                                if (!acked) {
                                    val queueBinding = queueBindings[queueID]
                                    if (queueBinding != null) {
                                        val queueName = queueBinding.queueName
                                        queueNames.add(queueName.toString())
                                    }
                                }
                            }
                            if (queueNames.size > 0 && (message.transactionID == -1L || pgTXs.contains(message.transactionID))) {
                                printSingleMessageAsXML(message.message, queueNames)
                            }
                            messageId++
                        }
                        pageId++
                    }
                }
                if (pageStore == null) {
                    ActiveMQServerLogger.LOGGER.debug("Page store was null")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Throws(XMLStreamException::class)
    private fun printSingleMessageAsXML(message: ServerMessage, queues: List<String>) {
        xmlWriter.writeStartElement(XmlDataConstants.MESSAGES_CHILD)
        printMessageAttributes(message)
        printMessageProperties(message)
        printMessageQueues(queues)
        printMessageBody(message)
        xmlWriter.writeEndElement() // end MESSAGES_CHILD
        messagesPrinted++
    }


    private fun printMessageBody(message: ServerMessage) {
        xmlWriter.writeStartElement(XmlDataConstants.MESSAGE_BODY)

        if (message.isLargeMessage) {
            printLargeMessageBody(message as LargeServerMessage)
        } else {
            val size = message.endOfBodyPosition - message.bodyBuffer.readerIndex()
            val buffer = ByteArray(size)
            message.bodyBuffer.readBytes(buffer)
            xmlWriter.writeCData(encode(buffer))
        }
        xmlWriter.writeEndElement() // end MESSAGE_BODY
    }


    private fun printLargeMessageBody(message: LargeServerMessage) {
        xmlWriter.writeAttribute(XmlDataConstants.MESSAGE_IS_LARGE, java.lang.Boolean.TRUE.toString())
        var encoder: BodyEncoder? = null

        try {
            encoder = message.bodyEncoder
            encoder!!.open()
            var totalBytesWritten: Long = 0
            var bufferSize: Long?
            val bodySize = encoder.largeBodySize
            var i: Long = 0
            while (i < bodySize) {
                val remainder = bodySize - totalBytesWritten
                if (remainder >= LARGE_MESSAGE_CHUNK_SIZE) {
                    bufferSize = LARGE_MESSAGE_CHUNK_SIZE
                } else {
                    bufferSize = remainder
                }
                val buffer = ActiveMQBuffers.fixedBuffer(bufferSize.toInt())
                encoder.encode(buffer, bufferSize.toInt())
                xmlWriter.writeCData(encode(buffer.toByteBuffer().array()))
                totalBytesWritten += bufferSize
                i += LARGE_MESSAGE_CHUNK_SIZE
            }
            encoder.close()
        } catch (e: ActiveMQException) {
            e.printStackTrace()
        } finally {
            if (encoder != null) {
                try {
                    encoder.close()
                } catch (e: ActiveMQException) {
                    e.printStackTrace()
                }

            }
        }
    }

    @Throws(XMLStreamException::class)
    private fun printMessageQueues(queues: List<String>) {
        xmlWriter.writeStartElement(XmlDataConstants.QUEUES_PARENT)
        for (queueName in queues) {
            xmlWriter.writeEmptyElement(XmlDataConstants.QUEUES_CHILD)
            xmlWriter.writeAttribute(XmlDataConstants.QUEUE_NAME, queueName)
        }
        xmlWriter.writeEndElement() // end QUEUES_PARENT
    }

    private fun printMessageProperties(message: ServerMessage) {

        xmlWriter.writeStartElement(XmlDataConstants.PROPERTIES_PARENT)
        for (key in message.propertyNames) {
            val value = message.getObjectProperty(key)
            xmlWriter.writeEmptyElement(XmlDataConstants.PROPERTIES_CHILD)
            xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_NAME, key.toString())

            if (value is ByteArray) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_VALUE, encode(value))
            } else {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_VALUE, if (value == null) XmlDataConstants.NULL else value.toString())
            }

            if (value is Boolean) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_BOOLEAN)
            } else if (value is Byte) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_BYTE)
            } else if (value is Short) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_SHORT)
            } else if (value is Int) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_INTEGER)
            } else if (value is Long) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_LONG)
            } else if (value is Float) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_FLOAT)
            } else if (value is Double) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_DOUBLE)
            } else if (value is String) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_STRING)
            } else if (value is SimpleString) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_SIMPLE_STRING)
            } else if (value is ByteArray) {
                xmlWriter.writeAttribute(XmlDataConstants.PROPERTY_TYPE, XmlDataConstants.PROPERTY_TYPE_BYTES)
            }
        }
        xmlWriter.writeEndElement() // end PROPERTIES_PARENT
    }

    @Throws(XMLStreamException::class)
    private fun printMessageAttributes(message: ServerMessage) {
        xmlWriter.writeAttribute(XmlDataConstants.MESSAGE_ID, java.lang.Long.toString(message.messageID))
        xmlWriter.writeAttribute(XmlDataConstants.MESSAGE_PRIORITY, java.lang.Byte.toString(message.priority))
        xmlWriter.writeAttribute(XmlDataConstants.MESSAGE_EXPIRATION, java.lang.Long.toString(message.expiration))
        xmlWriter.writeAttribute(XmlDataConstants.MESSAGE_TIMESTAMP, java.lang.Long.toString(message.timestamp))

        val prettyType = when (message.type) {
            Message.BYTES_TYPE -> {
                XmlDataConstants.BYTES_TYPE_PRETTY
            }
            Message.MAP_TYPE -> {
                XmlDataConstants.MAP_TYPE_PRETTY
            }
            Message.OBJECT_TYPE -> {
                XmlDataConstants.OBJECT_TYPE_PRETTY
            }
            Message.STREAM_TYPE -> {
                XmlDataConstants.STREAM_TYPE_PRETTY
            }
            Message.TEXT_TYPE -> {
                XmlDataConstants.TEXT_TYPE_PRETTY
            }
            else -> {
                XmlDataConstants.DEFAULT_TYPE_PRETTY
            }
        }
        xmlWriter.writeAttribute(XmlDataConstants.MESSAGE_TYPE, prettyType)
        if (message.userID != null) {
            xmlWriter.writeAttribute(XmlDataConstants.MESSAGE_USER_ID, message.userID.toString())
        }
    }

    @Throws(XMLStreamException::class)
    private fun printJmsDestinationsAsXML() {
        xmlWriter.writeStartElement(XmlDataConstants.JMS_DESTINATIONS)
        for (jmsDestinationsKey in jmsDestinations.keys) {
            val jmsDestination = jmsDestinations[jmsDestinationsKey]!!
            xmlWriter.writeStartElement(XmlDataConstants.JMS_DESTINATION)

            xmlWriter.writeStartElement(XmlDataConstants.JMS_DESTINATION_NAME)
            xmlWriter.writeCharacters(jmsDestination.name)
            xmlWriter.writeEndElement()

            val selector = jmsDestination.selector
            if (selector != null && selector.length != 0) {
                xmlWriter.writeStartElement(XmlDataConstants.JMS_DESTINATION_SELECTOR)
                xmlWriter.writeCharacters(selector)
                xmlWriter.writeEndElement()
            }

            xmlWriter.writeStartElement(XmlDataConstants.JMS_DESTINATION_TYPE)
            xmlWriter.writeCharacters(jmsDestination.type.toString())
            xmlWriter.writeEndElement()

            xmlWriter.writeStartElement(XmlDataConstants.JMS_JNDI_ENTRIES)
            val jndi = jmsJNDI[Pair(jmsDestination.type, jmsDestination.name)]
            jndi?.let {
                for (jndiEntry in jndi.bindings) {
                    xmlWriter.writeStartElement(XmlDataConstants.JMS_JNDI_ENTRY)
                    xmlWriter.writeCharacters(jndiEntry)
                    xmlWriter.writeEndElement()
                }
            }

            xmlWriter.writeEndElement() // end jndi-entries
            xmlWriter.writeEndElement() // end JMS_CONNECTION_FACTORY
        }
        xmlWriter.writeEndElement()
    }

    private fun extractQueueNames(refMap: Map<Long, DescribeJournal.ReferenceDescribe>): List<String> {
        val queues = mutableListOf<String>()
        for (ref in refMap.values) {
            queueBindings[ref.refEncoding.queueID]?.let {
                queues.add(it.queueName.toString())
            }
        }
        return queues
    }

    companion object {
        private fun encode(data: ByteArray): String {
            return Base64.encodeBytes(data, 0, data.size, Base64.DONT_BREAK_LINES or Base64.URL_SAFE)
        }


        private val INDENT_CHAR = ' '

        private val LINE_SEPARATOR = System.getProperty("line.separator")
    }

    internal inner class PrettyPrintHandler(private val target: XMLStreamWriter) : InvocationHandler {

        private var depth = 0

        var wrap = true

        @Throws(Throwable::class)
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val m = method.name

            when (m) {
                "writeStartElement" -> {
                    target.writeCharacters(LINE_SEPARATOR)
                    target.writeCharacters(indent(depth))

                    depth++
                }
                "writeEndElement" -> {
                    depth--
                    if (wrap) {
                        target.writeCharacters(LINE_SEPARATOR)
                        target.writeCharacters(indent(depth))
                    }
                    wrap = true
                }
                "writeEmptyElement", "writeCData" -> {
                    target.writeCharacters(LINE_SEPARATOR)
                    target.writeCharacters(indent(depth))
                }
                "writeCharacters" -> wrap = false
            }

            if(args != null){
                method.invoke(target, *args)
            }else {
                method.invoke(target, *emptyArray())
            }

            return null
        }

        private fun indent(depth1: Int): String {
            var depth = depth1
            depth *= 3 // level of indentation
            val output = CharArray(depth)
            while (depth-- > 0) {
                output[depth] = INDENT_CHAR
            }
            return String(output)
        }


    }
}