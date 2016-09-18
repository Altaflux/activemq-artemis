package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Option
import java.io.File


open class OptionalLocking : LockAbstract(){

    @Option(name = arrayOf("--f"), description = "This will allow certain tools like print-data to be performed ignoring any running servers. WARNING: Changing data concurrently with a running broker may damage your data. Be careful with this option.")
    var ignoreLock: Boolean = false

    override fun lockCLI(lockPlace : File?){
        if(!ignoreLock){
            super.lockCLI(lockPlace)
        }
    }
}