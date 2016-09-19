package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Command
import java.io.File

@Command(name = "stop", description = "stops the broker instance")
class Stop : Configurable() {

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        val broker = brokerDTO
        val file = broker.server.configurationFile.parentFile
        val stopFile = File(file, "STOP_ME")
        stopFile.createNewFile()
        return null
    }
}