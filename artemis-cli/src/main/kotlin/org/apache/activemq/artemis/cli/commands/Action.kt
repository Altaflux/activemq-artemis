package org.apache.activemq.artemis.cli.commands

import java.io.File


interface Action {
    val verbose: Boolean
    val brokerInstance: String?
    val brokerHome: String?

    @Throws(Exception::class)
    fun execute(context: ActionContext): Any?

    fun setHomeValues(brokerHome: File?, brokerInstance: File?)

}