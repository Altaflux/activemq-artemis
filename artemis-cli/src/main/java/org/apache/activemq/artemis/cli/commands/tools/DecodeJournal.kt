package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Command
import io.airlift.airline.Option
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.core.io.nio.NIOSequentialFileFactory
import org.apache.activemq.artemis.core.journal.RecordInfo
import org.apache.activemq.artemis.core.journal.impl.JournalImpl
import org.apache.activemq.artemis.utils.Base64
import java.io.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Command(name = "decode", description = "Decode a journal's internal format into a new journal set of files")
class DecodeJournal : LockAbstract() {

    @Option(name = arrayOf("--directory"), description = "The journal folder (default journal folder from broker.xml)")
    var directory: String? = null

    @Option(name = arrayOf("--prefix"), description = "The journal prefix (default activemq-data)")
    var prefix = "activemq-data"

    @Option(name = arrayOf("--suffix"), description = "The journal suffix (default amq)")
    var suffix = "amq"

    @Option(name = arrayOf("--file-size"), description = "The journal size (default 10485760)")
    var size = 10485760

    @Option(name = arrayOf("--input"), description = "The input file name (default=exp.dmp)", required = true)
    var input = "exp.dmp"

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        directory = directory ?: fileConfiguration.journalDirectory
        try {
            importJournal(directory!!, prefix, suffix, 2, size, input)
        } catch (e: Exception) {
            treatError(e, "data", "decode")
        }
        return null
    }

    companion object {

        fun importJournal(directory: String, journalPrefix: String, journalSuffix: String, minFiles: Int, fileSize: Int, fileInput: String) {
            importJournal(directory, journalPrefix, journalSuffix, minFiles, fileSize, FileInputStream(File(fileInput)))
        }

        fun importJournal(directory: String, journalPrefix: String, journalSuffix: String, minFiles: Int, fileSize: Int, stream: InputStream) {
            importJournal(directory, journalPrefix, journalSuffix, minFiles, fileSize, InputStreamReader(stream))
        }

        fun importJournal(directory: String, journalPrefix: String, journalSuffix: String, minFiles: Int, fileSize: Int, reader: Reader) {
            File(directory).apply {
                if (exists()) {
                    if (!mkdirs()) {
                        System.err.println("Could not create directory " + directory)
                    }
                }
            }

            val nio = NIOSequentialFileFactory(File(directory), null, 1)
            val journal = JournalImpl(fileSize, minFiles, minFiles, 0, 0, nio, journalPrefix, journalSuffix, 1)
            if (journal.orderFiles().size != 0) {
                throw IllegalStateException("Import needs to create a brand new journal")
            }
            journal.start()

            // The journal is empty, as we checked already. Calling load just to initialize the internal data
            journal.loadInternalOnly()
            val buffReader = BufferedReader(reader)
            val journalRecords = journal.records
            var lineNumber = 0
            val txCounters = mutableMapOf<Long, AtomicInteger>()
            for (line in buffReader.readLines()) {
                lineNumber++

                val splitLine = line.split(",")
                if (splitLine[0] == "#File") {
                    txCounters.clear()
                    continue
                }
                val lineProperties = parseLine(splitLine.toTypedArray())
                val operation = lineProperties.getProperty("operation")
                try {
                    when (operation) {
                        "AddRecord" -> {
                            val info = parseRecord(lineProperties)
                            journal.appendAddRecord(info.id, info.userRecordType, info.data, false)
                        }
                        "AddRecordTX" -> {
                            val txID = parseLong("txID", lineProperties)
                            val counter = getCounter(txID, txCounters)
                            counter.incrementAndGet()
                            val info = parseRecord(lineProperties)
                            journal.appendAddRecordTransactional(txID, info.id, info.userRecordType, info.data)
                        }
                        "UpdateTX" -> {
                            val txID = parseLong("txID", lineProperties)
                            val counter = getCounter(txID, txCounters)
                            counter.incrementAndGet()
                            val info = parseRecord(lineProperties)
                            journal.appendUpdateRecordTransactional(txID, info.id, info.userRecordType, info.data)
                        }
                        "Update" -> {
                            val info = parseRecord(lineProperties)
                            journal.appendUpdateRecord(info.id, info.userRecordType, info.data, false)
                        }
                        "DeleteRecord" -> {
                            val id = parseLong("id", lineProperties)

                            // If not found it means the append/update records were reclaimed already
                            if (journalRecords[id] != null) {
                                journal.appendDeleteRecord(id, false)
                            }
                        }
                        "DeleteRecordTX" -> {
                            val txID = parseLong("txID", lineProperties)
                            val id = parseLong("id", lineProperties)
                            val counter = getCounter(txID, txCounters)
                            counter.incrementAndGet()

                            // If not found it means the append/update records were reclaimed already
                            if (journalRecords[id] != null) {
                                journal.appendDeleteRecordTransactional(txID, id)
                            }
                        }
                        "Prepare" -> {
                            val txID = parseLong("txID", lineProperties)
                            val numberOfRecords = parseInt("numberOfRecords", lineProperties)
                            val counter = getCounter(txID, txCounters)
                            val data = parseEncoding("extraData", lineProperties)

                            if (counter.get() == numberOfRecords) {
                                journal.appendPrepareRecord(txID, data, false)
                            } else {
                                System.err.println("Transaction " + txID +
                                        " at line " +
                                        lineNumber +
                                        " is incomplete. The prepare record expected " +
                                        numberOfRecords +
                                        " while the import only had " +
                                        counter)
                            }
                        }
                        "Commit" -> {
                            val txID = parseLong("txID", lineProperties)
                            val numberOfRecords = parseInt("numberOfRecords", lineProperties)
                            val counter = getCounter(txID, txCounters)
                            if (counter.get() == numberOfRecords) {
                                journal.appendCommitRecord(txID, false)
                            } else {
                                System.err.println("Transaction " + txID +
                                        " at line " +
                                        lineNumber +
                                        " is incomplete. The commit record expected " +
                                        numberOfRecords +
                                        " while the import only had " +
                                        counter)
                            }
                        }
                        "Rollback" -> {
                            val txID = parseLong("txID", lineProperties)
                            journal.appendRollbackRecord(txID, false)
                        }
                        else -> {
                            System.err.println("Invalid operation $operation at line $lineNumber")
                        }
                    }
                } catch (ex: Exception) {
                    System.err.println("Error at line " + lineNumber + ", operation=" + operation + " msg = " + ex.message)
                }

            }

        }

        fun getCounter(txID: Long, txCounters: MutableMap<Long, AtomicInteger>): AtomicInteger {
            var counter: AtomicInteger? = txCounters[txID]
            if (counter == null) {
                counter = AtomicInteger(0)
                txCounters.put(txID, counter)
            }

            return counter
        }

        @Throws(Exception::class)
        fun parseRecord(properties: Properties): RecordInfo {
            val id = parseLong("id", properties)
            val userRecordType = parseByte("userRecordType", properties)
            val isUpdate = parseBoolean("isUpdate", properties)
            val data = parseEncoding("data", properties)
            return RecordInfo(id, userRecordType, data, isUpdate, 0.toShort())
        }

        @Throws(Exception::class)
        private fun parseEncoding(name: String, properties: Properties): ByteArray {
            val value = parseString(name, properties)

            return decode(value)
        }

        @Throws(Exception::class)
        private fun parseInt(name: String, properties: Properties): Int {
            val value = parseString(name, properties)

            return Integer.parseInt(value)
        }

        @Throws(Exception::class)
        private fun parseLong(name: String, properties: Properties): Long {
            val value = parseString(name, properties)

            return java.lang.Long.parseLong(value)
        }

        @Throws(Exception::class)
        private fun parseBoolean(name: String, properties: Properties): Boolean {
            val value = parseString(name, properties)

            return java.lang.Boolean.parseBoolean(value)
        }

        @Throws(Exception::class)
        private fun parseByte(name: String, properties: Properties): Byte {
            val value = parseString(name, properties)

            return java.lang.Byte.parseByte(value)
        }


        @Throws(Exception::class)
        private fun parseString(name: String, properties: Properties): String {
            val value = properties.getProperty(name) ?: throw Exception("property $name not found")

            return value
        }

        fun parseLine(splitLine: Array<String>): Properties {
            val properties = Properties()

            for (el in splitLine) {
                val tuple = el.split("@".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (tuple.size == 2) {
                    properties.put(tuple[0], tuple[1])
                } else {
                    properties.put(tuple[0], tuple[0])
                }
            }

            return properties
        }

        private fun decode(data: String): ByteArray {
            return Base64.decode(data, Base64.DONT_BREAK_LINES or Base64.URL_SAFE)
        }
    }

}