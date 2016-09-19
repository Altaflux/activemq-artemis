package org.apache.activemq.artemis.cli.commands.destination

import io.airlift.airline.Help
import org.apache.activemq.artemis.cli.commands.Action
import org.apache.activemq.artemis.cli.commands.ActionContext
import java.io.File


class HelpDestination : Help(), Action {

    override val verbose = false

    override fun setHomeValues(brokerHome: File?, brokerInstance: File?) {

    }

    override fun execute(context: ActionContext): Any? {
        val commands = mutableListOf("destination")
        help(global, commands)
        return null
    }

    override val brokerInstance = null
    override val brokerHome = null
}