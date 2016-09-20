package org.apache.activemq.artemis.cli.commands

import java.io.InputStream
import java.io.PrintStream


class ActionContext @JvmOverloads constructor(var `in`: InputStream = System.`in`, var out: PrintStream = System.out, var err: PrintStream = System.err) {
    companion object {
        fun system(): ActionContext {
            return ActionContext(System.`in`, System.out, System.err)
        }
    }
}
