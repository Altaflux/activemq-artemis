package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Command
import java.io.File

@Command(name = "kill", description = "Kills a broker instance started with --allow-kill")
class Kill : Configurable() {

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        val broker = brokerDTO
        val file = broker.server.configurationFile.parentFile
        File(file, "KILL_ME").run { createNewFile() }
        return null
    }
}