package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Help
import java.io.File


class HelpAction : Help(), Action {

    override fun execute(context: ActionContext): Any? {
        super.run()
        return null
    }

    override val verbose = false
    override val brokerInstance = null
    override val brokerHome = null

    override fun setHomeValues(brokerHome: File?, brokerInstance: File?) {
    }


}