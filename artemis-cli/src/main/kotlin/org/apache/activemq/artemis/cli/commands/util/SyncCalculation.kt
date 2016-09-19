package org.apache.activemq.artemis.cli.commands.util

import org.apache.activemq.artemis.core.io.IOCallback
import org.apache.activemq.artemis.core.io.SequentialFileFactory
import org.apache.activemq.artemis.core.io.aio.AIOSequentialFileFactory
import org.apache.activemq.artemis.core.io.nio.NIOSequentialFileFactory
import org.apache.activemq.artemis.jlibaio.LibaioContext
import org.apache.activemq.artemis.utils.ReusableLatch
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit


object SyncCalculation {

    fun syncTest(datafolder: File, blockSize: Int, blocks: Int, tries: Int, verbose: Boolean, aio: Boolean): Long {
        val factory = newFactory(datafolder, aio)
        val file = factory.createSequentialFile("test.tmp")

        try {
            file.delete()
            file.open()

            file.fill(blockSize * blocks)
            val result = LongArray(tries)
            val block = ByteArray(blockSize)

            for (i in block.indices) {
                block[i] = 't'.toByte()
            }
            val bufferBlock = factory.newBuffer(blockSize)
            bufferBlock.put(block)
            bufferBlock.position(0)
            val latch = ReusableLatch(0)
            val callback = object : IOCallback {
                override fun done() {
                    latch.countDown()
                }

                override fun onError(errorCode: Int, errorMessage: String) {

                }
            }
            val dcformat = DecimalFormat("###.##")
            for (ntry in 0..tries - 1) {
                if (verbose) {
                    println("**************************************************")
                    println("$ntry of $tries calculation")
                }
                file.position(0)
                val start = System.currentTimeMillis()
                for (i in 0..blocks - 1) {
                    bufferBlock.position(0)
                    latch.countUp()
                    file.writeDirect(bufferBlock, true, callback)
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw IOException("Callback wasn't called")
                    }
                }
                val end = System.currentTimeMillis()
                result[ntry] = end - start

                if (verbose) {
                    val writesPerMillisecond = blocks.toDouble() / result[ntry].toDouble()
                    println("Time = ${result[ntry]}")
                    println("Writes / millisecond = ${dcformat.format(writesPerMillisecond)}")
                    println("bufferTimeout = ${toNanos(result[ntry], blocks.toLong())}")
                    println("**************************************************")
                }
            }
            factory.releaseDirectBuffer(bufferBlock)

            var totalTime = java.lang.Long.MAX_VALUE
            for (i in 0..tries - 1) {
                if (result[i] < totalTime) {
                    totalTime = result[i]
                }
            }
            return totalTime
        } finally {
            try {
                file.close()
            } catch (e: Exception) {
            }

            try {
                file.delete()
            } catch (e: Exception) {
            }

            try {
                factory.stop()
            } catch (e: Exception) {
            }

        }
    }


    fun toNanos(time: Long, blocks: Long): Long {
        val blocksPerMillisecond = blocks.toDouble() / time.toDouble()
        val nanoSeconds = TimeUnit.NANOSECONDS.convert(1, TimeUnit.MILLISECONDS)
        val timeWait = (nanoSeconds / blocksPerMillisecond).toLong()
        return timeWait
    }

    private fun newFactory(datafolder: File, aio: Boolean): SequentialFileFactory {
        if (aio && LibaioContext.isLoaded()) {
            val factory = AIOSequentialFileFactory(datafolder, 1)
            factory.start()
            factory.disableBufferReuse()
            return factory
        } else {
            val factory = NIOSequentialFileFactory(datafolder, 1)
            factory.start()
            return factory
        }
    }
}