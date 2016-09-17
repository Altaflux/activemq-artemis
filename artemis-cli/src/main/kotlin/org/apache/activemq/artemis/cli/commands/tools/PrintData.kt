package org.apache.activemq.artemis.cli.commands.tools

import io.airlift.airline.Command
import org.apache.activemq.artemis.api.core.ActiveMQBuffers
import org.apache.activemq.artemis.cli.Artemis
import org.apache.activemq.artemis.cli.commands.ActionContext
import org.apache.activemq.artemis.core.journal.RecordInfo
import org.apache.activemq.artemis.core.paging.cursor.PagePosition
import org.apache.activemq.artemis.core.paging.cursor.impl.PagePositionImpl
import org.apache.activemq.artemis.core.paging.impl.PageTransactionInfoImpl
import org.apache.activemq.artemis.core.paging.impl.PagingManagerImpl
import org.apache.activemq.artemis.core.paging.impl.PagingStoreFactoryNIO
import org.apache.activemq.artemis.core.persistence.impl.journal.DescribeJournal
import org.apache.activemq.artemis.core.persistence.impl.journal.JournalRecordIds
import org.apache.activemq.artemis.core.persistence.impl.journal.codec.CursorAckRecordEncoding
import org.apache.activemq.artemis.core.persistence.impl.journal.codec.PageUpdateTXEncoding
import org.apache.activemq.artemis.core.persistence.impl.nullpm.NullStorageManager
import org.apache.activemq.artemis.core.server.impl.FileLockNodeManager
import org.apache.activemq.artemis.core.settings.impl.AddressSettings
import org.apache.activemq.artemis.core.settings.impl.HierarchicalObjectRepository
import org.apache.activemq.artemis.utils.ActiveMQThreadFactory
import org.apache.activemq.artemis.utils.ExecutorFactory
import java.io.File
import java.util.concurrent.Executors


@Command(name = "print", description = "Print data records information (WARNING: don't use while a production server is running)")
class PrintData : OptionalLocking() {

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        try {
            printData(File(getBinding()), File(getJournal()), File(getPaging()))
        } catch (e: Exception) {
            treatError(e, "data", "print")
        }

        return null
    }

    companion object {


        fun printData(bindingsDirectory: File, messagesDirectory: File, pagingDirectory: File) {
            // Having the version on the data report is an information very useful to understand what happened
            // When debugging stuff
            Artemis.printBanner()
            val serverLockFile = File(messagesDirectory, "server.lock")
            if (serverLockFile.isFile) {
                try {
                    val fileLock = FileLockNodeManager(messagesDirectory, false)
                    fileLock.start()
                    println("********************************************")
                    println("Server's ID=" + fileLock.nodeId.toString())
                    println("********************************************")
                    fileLock.stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            println("********************************************")
            println("B I N D I N G S  J O U R N A L")
            println("********************************************")
            try {
                DescribeJournal.describeBindingsJournal(bindingsDirectory)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            println()
            println("********************************************")
            println("M E S S A G E S   J O U R N A L")
            println("********************************************")

            val describeJournal = try {
                DescribeJournal.describeMessagesJournal(messagesDirectory)
            } catch (e: Exception) {
                return
            }
            try {
                println()
                println("********************************************")
                println("P A G I N G")
                println("********************************************")

                printPages(pagingDirectory, describeJournal)
            } catch (e: Exception) {
                e.printStackTrace()
                return
            }

        }

        fun printPages(pageDirectory: File, describeJournal: DescribeJournal) {
            try {
                val cursorACKs = calculateCursorsInfo(describeJournal.records)

                val pgTXs = cursorACKs.pgTXs
                val scheduled = Executors.newScheduledThreadPool(1, ActiveMQThreadFactory.defaultThreadFactory())
                val executor = Executors.newFixedThreadPool(10, ActiveMQThreadFactory.defaultThreadFactory())
                val execfactory = ExecutorFactory { executor }
                val sm = NullStorageManager()
                val pageStoreFactory = PagingStoreFactoryNIO(sm, pageDirectory, 1000L, scheduled, execfactory, false, null)

                val addressSettingsRepository = HierarchicalObjectRepository<AddressSettings>()
                addressSettingsRepository.setDefault(AddressSettings())
                val manager = PagingManagerImpl(pageStoreFactory, addressSettingsRepository)
                manager.start()

                val stores = manager.storeNames

                for (store in stores) {
                    val pgStore = manager.getPageStore(store)

                    val folder = pgStore.folder
                    println("####################################################################################################")
                    println("Exploring store $store folder = $folder")
                    var pgid = pgStore.firstPage.toInt()
                    for (i in 0..pgStore.numberOfPages - 1) {
                        println("*******   Page " + pgid)
                        val page = pgStore.createPage(pgid)
                        page.open()
                        val msgs = page.read(sm)
                        page.close()
                        var msgID = 0
                        for (msg in msgs) {
                            msg.initMessage(sm)
                            print("pg=" + pgid + ", msg=" + msgID + ",pgTX=" + msg.transactionID + ",userMessageID=" + (if (msg.message.userID != null) msg.message.userID else "") + ", msg=" + msg.message)
                            print(",Queues = ")
                            val q = msg.queueIDs
                            for (value in q) {
                                println(value)
                                val posCheck = PagePositionImpl(pgid.toLong(), msgID)
                                var acked = false
                                val positions = cursorACKs.cursorRecords[value]
                                if (positions != null) {
                                    acked = positions.contains(posCheck)
                                }
                                if (acked) {
                                    print(" (ACK)")
                                }
                                if (cursorACKs.completePages[value]!!.contains(pgid.toLong())) {
                                    println(" (PG-COMPLETE)")
                                }
                                if (i + 1 < q.size) {
                                    print(",")
                                }
                            }
                            if (msg.transactionID >= 0 && !pgTXs.contains(msg.transactionID)) {
                                print(", **PG_TX_NOT_FOUND**")
                            }
                            println()
                            msgID++
                        }
                        pgid++
                    }

                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        fun calculateCursorsInfo(records: List<RecordInfo>): PageCursorsInfo {
            val cursorInfo = PageCursorsInfo()
            for (record in records) {
                val data = record.data
                val buff = ActiveMQBuffers.wrappedBuffer(data)
                if (record.userRecordType == JournalRecordIds.ACKNOWLEDGE_CURSOR) {
                    val encoding = CursorAckRecordEncoding()
                    encoding.decode(buff)
                    var set = cursorInfo.cursorRecords[encoding.queueID] as MutableSet<PagePosition>?
                    if (set == null) {
                        set = mutableSetOf()
                        cursorInfo.cursorRecords[encoding.queueID] = set
                    }
                    set.add(encoding.position)
                } else if (record.userRecordType == JournalRecordIds.PAGE_CURSOR_COMPLETE) {
                    val encoding = CursorAckRecordEncoding()
                    encoding.decode(buff)
                    val queueID = encoding.queueID
                    val pageNR = encoding.position.pageNr
                    if (!(cursorInfo.getCompletePages(queueID) as MutableSet<Long>).add(pageNR)) {
                        System.err.println("Page $pageNR has been already set as complete on queue $queueID")
                    }
                } else if (record.userRecordType == JournalRecordIds.PAGE_TRANSACTION) {
                    if (record.isUpdate) {
                        val pageUpdate = PageUpdateTXEncoding()
                        pageUpdate.decode(buff)
                        cursorInfo.pgTXs.add(pageUpdate.pageTX)
                    } else {
                        val pageTransactionInfo = PageTransactionInfoImpl()
                        pageTransactionInfo.decode(buff)
                        pageTransactionInfo.recordID = record.id
                        cursorInfo.pgTXs.add(pageTransactionInfo.transactionID)
                    }
                }
            }

            return cursorInfo
        }
    }

    class PageCursorsInfo {
        val cursorRecords = mutableMapOf<Long, Set<PagePosition>>()
        val pgTXs = mutableSetOf<Long>()
        val completePages = mutableMapOf<Long, Set<Long>>()

        fun getCompletePages(queueID: Long): Set<Long> {
            var completePageSet = completePages[queueID]
            if (completePageSet == null) {
                completePageSet = mutableSetOf()
                completePages[queueID] = completePageSet
            }
            return completePageSet
        }
    }

}