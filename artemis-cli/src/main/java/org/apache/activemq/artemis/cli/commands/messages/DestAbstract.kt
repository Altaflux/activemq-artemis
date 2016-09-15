package org.apache.activemq.artemis.cli.commands.messages

import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.commands.ActionAbstract


open class DestAbstract : ActionAbstract() {

    @Option(name = arrayOf("--url"), description = "URL towards the broker. (default: tcp://localhost:61616)")
    var brokerURL = "tcp://localhost:61616"

    @Option(name = arrayOf("--destination"), description = "Destination to be used. it could be prefixed with queue:// or topic:: (Default: queue://TEST")
    var destination = "queue://TEST"

    @Option(name = arrayOf("--message-count"), description = "Number of messages to act on (Default: 1000)")
    var messageCount = 1000

    @Option(name = arrayOf("--user"), description = "User used to connect")
    var user: String? = null

    @Option(name = arrayOf("--password"), description = "Password used to connect")
    var password: String? = null

    @Option(name = arrayOf("--sleep"), description = "Time wait between each message")
    var sleep = 0

    @Option(name = arrayOf("--txt-size"), description = "TX Batch Size")
    var txBatchSize: Int = 0

    @Option(name = arrayOf("--threads"), description = "Number of Threads to be used (Default: 1)")
    var threads = 1
}