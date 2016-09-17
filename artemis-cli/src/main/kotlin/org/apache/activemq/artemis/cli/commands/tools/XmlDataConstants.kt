package org.apache.activemq.artemis.cli.commands.tools


class XmlDataConstants {
    private constructor()

    companion object {

        const val XML_VERSION = "1.0"

        const val DOCUMENT_PARENT = "activemq-journal"

        const val BINDINGS_PARENT = "bindings"

        const val BINDINGS_CHILD = "binding"

        const val BINDING_ADDRESS = "address"

        const val BINDING_FILTER_STRING = "filter-string"

        const val BINDING_QUEUE_NAME = "queue-name"

        const val BINDING_ID = "id"

        const val JMS_CONNECTION_FACTORY = "jms-connection-factory"

        const val JMS_CONNECTION_FACTORIES = "jms-connection-factories"

        const val MESSAGES_PARENT = "messages"

        const val MESSAGES_CHILD = "message"

        const val MESSAGE_ID = "id"

        const val MESSAGE_PRIORITY = "priority"

        const val MESSAGE_EXPIRATION = "expiration"

        const val MESSAGE_TIMESTAMP = "timestamp"

        const val DEFAULT_TYPE_PRETTY = "default"

        const val BYTES_TYPE_PRETTY = "bytes"

        const val MAP_TYPE_PRETTY = "map"

        const val OBJECT_TYPE_PRETTY = "object"

        const val STREAM_TYPE_PRETTY = "stream"

        const val TEXT_TYPE_PRETTY = "text"

        const val MESSAGE_TYPE = "type"

        const val MESSAGE_IS_LARGE = "isLarge"

        const val MESSAGE_USER_ID = "user-id"

        const val MESSAGE_BODY = "body"

        const val PROPERTIES_PARENT = "properties"

        const val PROPERTIES_CHILD = "property"

        const val PROPERTY_NAME = "name"

        const val PROPERTY_VALUE = "value"

        const val PROPERTY_TYPE = "type"

        const val QUEUES_PARENT = "queues"

        const val QUEUES_CHILD = "queue"

        const val QUEUE_NAME = "name"

        const val PROPERTY_TYPE_BOOLEAN = "boolean"

        const val PROPERTY_TYPE_BYTE = "byte"

        const val PROPERTY_TYPE_BYTES = "bytes"

        const val PROPERTY_TYPE_SHORT = "short"

        const val PROPERTY_TYPE_INTEGER = "integer"

        const val PROPERTY_TYPE_LONG = "long"

        const val PROPERTY_TYPE_FLOAT = "float"

        const val PROPERTY_TYPE_DOUBLE = "double"

        const val PROPERTY_TYPE_STRING = "string"

        const val PROPERTY_TYPE_SIMPLE_STRING = "simple-string"


        const val JMS_CONNECTION_FACTORY_NAME = "name"

        const val JMS_CONNECTION_FACTORY_CLIENT_ID = "client-id"

        const val JMS_CONNECTION_FACTORY_CALL_FAILOVER_TIMEOUT = "call-failover-timeout"

        const val JMS_CONNECTION_FACTORY_CALL_TIMEOUT = "call-timeout"

        const val JMS_CONNECTION_FACTORY_CLIENT_FAILURE_CHECK_PERIOD = "client-failure-check-period"
        const val JMS_CONNECTION_FACTORY_CONFIRMATION_WINDOW_SIZE = "confirmation-window-size"
        const val JMS_CONNECTION_FACTORY_CONNECTION_TTL = "connection-ttl"
        const val JMS_CONNECTION_FACTORY_CONSUMER_MAX_RATE = "consumer-max-rate"
        const val JMS_CONNECTION_FACTORY_CONSUMER_WINDOW_SIZE = "consumer-window-size"
        const val JMS_CONNECTION_FACTORY_DISCOVERY_GROUP_NAME = "discovery-group-name"
        const val JMS_CONNECTION_FACTORY_DUPS_OK_BATCH_SIZE = "dups-ok-batch-size"
        const val JMS_CONNECTION_FACTORY_TYPE = "type"
        const val JMS_CONNECTION_FACTORY_GROUP_ID = "group-id"
        const val JMS_CONNECTION_FACTORY_LOAD_BALANCING_POLICY_CLASS_NAME = "load-balancing-policy-class-name"
        const val JMS_CONNECTION_FACTORY_MAX_RETRY_INTERVAL = "max-retry-interval"
        const val JMS_CONNECTION_FACTORY_MIN_LARGE_MESSAGE_SIZE = "min-large-message-size"
        const val JMS_CONNECTION_FACTORY_PRODUCER_MAX_RATE = "producer-max-rate"
        const val JMS_CONNECTION_FACTORY_PRODUCER_WINDOW_SIZE = "producer-window-size"
        const val JMS_CONNECTION_FACTORY_RECONNECT_ATTEMPTS = "reconnect-attempts"
        const val JMS_CONNECTION_FACTORY_RETRY_INTERVAL = "retry-interval"
        const val JMS_CONNECTION_FACTORY_RETRY_INTERVAL_MULTIPLIER = "retry-interval-multiplier"
        const val JMS_CONNECTION_FACTORY_SCHEDULED_THREAD_POOL_MAX_SIZE = "scheduled-thread-pool-max-size"
        const val JMS_CONNECTION_FACTORY_THREAD_POOL_MAX_SIZE = "thread-pool-max-size"
        const val JMS_CONNECTION_FACTORY_TRANSACTION_BATCH_SIZE = "transaction-batch-size"
        const val JMS_CONNECTION_FACTORY_CONNECTORS = "connectors"
        const val JMS_CONNECTION_FACTORY_CONNECTOR = "connector"
        const val JMS_CONNECTION_FACTORY_AUTO_GROUP = "auto-group"
        const val JMS_CONNECTION_FACTORY_BLOCK_ON_ACKNOWLEDGE = "block-on-acknowledge"
        const val JMS_CONNECTION_FACTORY_BLOCK_ON_DURABLE_SEND = "block-on-durable-send"
        const val JMS_CONNECTION_FACTORY_BLOCK_ON_NON_DURABLE_SEND = "block-on-non-durable-send"
        const val JMS_CONNECTION_FACTORY_CACHE_LARGE_MESSAGES_CLIENT = "cache-large-messages-client"
        const val JMS_CONNECTION_FACTORY_COMPRESS_LARGE_MESSAGES = "compress-large-messages"
        const val JMS_CONNECTION_FACTORY_FAILOVER_ON_INITIAL_CONNECTION = "failover-on-initial-connection"
        const val JMS_CONNECTION_FACTORY_HA = "ha"
        const val JMS_CONNECTION_FACTORY_PREACKNOWLEDGE = "preacknowledge"
        const val JMS_CONNECTION_FACTORY_USE_GLOBAL_POOLS = "use-global-pools"

        const val JMS_DESTINATIONS = "jms-destinations"
        const val JMS_DESTINATION = "jms-destination"
        const val JMS_DESTINATION_NAME = "name"
        const val JMS_DESTINATION_SELECTOR = "selector"
        const val JMS_DESTINATION_TYPE = "type"

        const val JMS_JNDI_ENTRIES = "entries"
        const val JMS_JNDI_ENTRY = "entry"

        val JNDI_COMPATIBILITY_PREFIX = "java:jboss/exported/"

        const val NULL = "_AMQ_NULL"
    }
}