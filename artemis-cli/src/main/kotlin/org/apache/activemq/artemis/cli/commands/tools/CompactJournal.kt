package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Command
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.core.io.IOCriticalErrorListener
import org.apache.activemq.artemis.core.io.nio.NIOSequentialFileFactory
import org.apache.activemq.artemis.core.journal.impl.JournalImpl
import java.io.File

@Command(name = "compact", description = "Compacts the journal of a non running server")
class CompactJournal : LockAbstract() {

    override fun execute(context: ActionContext): Any? {
        try {
            val configuration = fileConfiguration
            compactJournal(File(journal), "activemq-data", "amq", configuration.journalMinFiles, configuration.journalFileSize, null)
            println("Compactation succeeded for " + journal)
            compactJournal(File(binding), "activemq-bindings", "bindings", 2, 1048576, null)
            println("Compactation succeeded for " + binding)
        } catch (e: Exception) {
            treatError(e, "data", "compact")
        }
        return null
    }

    private fun compactJournal(directory: File, journalPrefix: String, journalSuffix: String, minFiles: Int, fileSize: Int,
                               listener: IOCriticalErrorListener?) {
        val nio = NIOSequentialFileFactory(directory, listener, 1)
        val journal = JournalImpl(fileSize, minFiles, minFiles, 0, 0, nio, journalPrefix, journalSuffix, 1)
        journal.start()
        journal.loadInternalOnly()
        journal.compact()
        journal.stop()
    }
}