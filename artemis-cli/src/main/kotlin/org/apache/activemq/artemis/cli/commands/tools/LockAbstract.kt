package org.apache.activemq.artemis.cli.commands.tools

import org.apache.activemq.artemis.cli.CLIException
import org.apache.activemq.artemis.cli.commands.Action
import org.apache.activemq.artemis.cli.commands.ActionContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException


abstract class LockAbstract : DataAbstract(), Action {

    @Throws(Exception::class)
    protected fun getLockPlace(): File? {
        val brokerInstance = brokerInstance
        if (brokerInstance != null) {
            return File(File(brokerInstance), "lock")
        } else {
            return null
        }
    }

    @Throws(Exception::class)
    override fun execute(context: ActionContext): Any? {
        super.execute(context)

        if (brokerInstance == null) {
            System.err.println("Warning: You are running a data tool outside of any broker instance. Modifying data on a running server might break the server's data")
            System.err.println()
        } else {
            lockCLI(getLockPlace())
        }

        return null
    }

    protected open fun lockCLI(lockPlace: File?) {
        if (lockPlace != null) {
            lockPlace.mkdirs()
            if (serverLockFile == null) {
                val fileLock = File(lockPlace, "cli.lock")
                serverLockFile = RandomAccessFile(fileLock, "rw")
            }
            serverLockFile?.let {
                try {
                    val lock = it.channel.tryLock() ?: throw CLIException("Error: There is another process using the server at $lockPlace. Cannot start the process!")
                    serverLockLock = lock
                } catch (e: OverlappingFileLockException) {
                    throw CLIException("Error: There is another process using the server at $lockPlace. Cannot start the process!")
                }
            }


        }
    }

    companion object {
        @JvmStatic
        var serverLockFile: RandomAccessFile? = null

        @JvmStatic
        var serverLockLock: FileLock? = null

        @JvmStatic
        fun unlock() {
            try {
                serverLockFile?.let { it.close() }
                serverLockFile = null
                serverLockLock?.let { it.close() }
                serverLockLock = null
            } catch (e: Exception) {

            }
        }
    }
}