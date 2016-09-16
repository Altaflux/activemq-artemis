package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.core.io.SequentialFileFactory
import org.apache.activemq.artemis.core.io.nio.NIOSequentialFileFactory
import org.apache.activemq.artemis.core.journal.RecordInfo
import org.apache.activemq.artemis.core.journal.impl.JournalFile
import org.apache.activemq.artemis.core.journal.impl.JournalImpl
import org.apache.activemq.artemis.core.journal.impl.JournalReaderCallback
import org.apache.activemq.artemis.utils.Base64
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream

@Command(name = "encode", description = "Encode a set of journal files into an internal encoded data format")
class EncodeJournal : LockAbstract() {

    @Option(name = arrayOf("--directory"), description = "The journal folder (default the journal folder from broker.xml)")
    var directory: String? = null

    @Option(name = arrayOf("--prefix"), description = "The journal prefix (default activemq-data)")
    var prefix = "activemq-data"

    @Option(name = arrayOf("--suffix"), description = "The journal suffix (default amq)")
    var suffix = "amq"

    @Option(name = arrayOf("--file-size"), description = "The journal size (default 10485760)")
    var size = 10485760

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        try {
            directory ?: fileConfiguration.journalDirectory
            exportJournal(directory!!, prefix, suffix, 2, size)
        } catch (e: Exception) {
            treatError(e, "data", "encode")
        }
        return null
    }

    companion object {

        @JvmStatic
        fun exportJournal(directory: String, journalPrefix: String, journalSuffix: String, minFiles: Int, fileSize: Int) {
            exportJournal(directory, journalPrefix, journalSuffix, minFiles, fileSize, System.out)
        }


        @JvmStatic
        fun exportJournal(directory: String, journalPrefix: String, journalSuffix: String, minFiles: Int, fileSize: Int, fileName: String) {
            FileOutputStream(fileName).use { fileOutputStream ->
                BufferedOutputStream(fileOutputStream).use { bufferedOutputStream ->
                    PrintStream(bufferedOutputStream).use { out ->
                        exportJournal(directory, journalPrefix, journalSuffix, minFiles, fileSize, out)
                    }
                }
            }
        }
        @JvmStatic
        fun exportJournal(directory: String, journalPrefix: String, journalSuffix: String, minFiles: Int, fileSize: Int, out: PrintStream) {
            val nio = NIOSequentialFileFactory(File(directory), null, 1)
            val journal = JournalImpl(fileSize, minFiles, minFiles, 0, 0, nio, journalPrefix, journalSuffix, 1)
            val files = journal.orderFiles()

            for (file in files) {
                out.println("#File," + file)
                exportJournalFile(out, nio, file)
            }
        }

        private fun exportJournalFile(out: PrintStream, fileFactory: SequentialFileFactory, file: JournalFile) {
            JournalImpl.readJournalFile(fileFactory, file, object : JournalReaderCallback {
                override fun onReadAddRecord(info: RecordInfo) {
                    out.println("operation@AddRecord," + describeRecord(info))
                }

                override fun onReadUpdateRecord(recordInfo: RecordInfo) {
                    out.println("operation@Update," + describeRecord(recordInfo))
                }

                override fun onReadDeleteRecord(recordID: Long) {
                    out.println("operation@DeleteRecord,id@" + recordID)
                }

                override fun onReadAddRecordTX(transactionID: Long, recordInfo: RecordInfo) {
                    out.println("operation@AddRecordTX,txID@" + transactionID + "," + describeRecord(recordInfo))
                }

                override fun onReadUpdateRecordTX(transactionID: Long, recordInfo: RecordInfo) {
                    out.println("operation@UpdateTX,txID@" + transactionID + "," + describeRecord(recordInfo))
                }

                override fun onReadDeleteRecordTX(transactionID: Long, recordInfo: RecordInfo) {
                    out.println("operation@DeleteRecordTX,txID@" + transactionID +
                            "," +
                            describeRecord(recordInfo))
                }

                override fun onReadPrepareRecord(transactionID: Long, extraData: ByteArray, numberOfRecords: Int) {
                    out.println("operation@Prepare,txID@" + transactionID +
                            ",numberOfRecords@" +
                            numberOfRecords +
                            ",extraData@" +
                            encode(extraData))
                }

                override fun onReadCommitRecord(transactionID: Long, numberOfRecords: Int) {
                    out.println("operation@Commit,txID@$transactionID,numberOfRecords@$numberOfRecords")
                }

                override fun onReadRollbackRecord(transactionID: Long) {
                    out.println("operation@Rollback,txID@" + transactionID)
                }

                override fun markAsDataFile(file: JournalFile) {

                }

            })
        }

        private fun describeRecord(recordInfo: RecordInfo): String {
            return "id@" + recordInfo.id +
                    ",userRecordType@" +
                    recordInfo.userRecordType +
                    ",length@" +
                    recordInfo.data.size +
                    ",isUpdate@" +
                    recordInfo.isUpdate +
                    ",compactCount@" +
                    recordInfo.compactCount +
                    ",data@" +
                    encode(recordInfo.data)
        }

        private fun encode(data: ByteArray): String {
            return Base64.encodeBytes(data, 0, data.size, Base64.DONT_BREAK_LINES or Base64.URL_SAFE)
        }
    }
}