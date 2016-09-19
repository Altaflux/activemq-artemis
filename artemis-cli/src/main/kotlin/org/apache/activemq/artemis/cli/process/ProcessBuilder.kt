package org.apache.activemq.artemis.cli.process


import org.apache.activemq.artemis.utils.ConcurrentHashSet
import java.io.*


object ProcessBuilder {

    var processes = ConcurrentHashSet<Process>()

    init {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                for (p in processes) {
                    run { p.destroy() }
                }
            }
        })
    }

    /**
     * it will lookup for process that are dead already, eliminating leaks.
     */
    fun cleanupProcess() {
        for (p in processes) {
            processes.remove(p)
        }
    }


    /**
     * Hola Mundo
     * @param logname  the prefix for log output
     * *
     * @param location The location where this command is being executed from
     * @param hook     it will finish the process upon shutdown of the VM
     * @param args     The arguments being passwed to the the CLI tool
     * @return
     * @throws Exception
     */
    @JvmStatic
    @Throws(Exception::class)
    fun build(logname: String, location: File, hook: Boolean, vararg args: String): Process {
        val IS_WINDOWS = System.getProperty("os.name").toLowerCase().trim { it <= ' ' }.startsWith("win")

        val newArgs: Array<String>
        if (IS_WINDOWS) {
            newArgs = rebuildArgs(args.toList().toTypedArray(), "cmd", "/c", "artemis.cmd")
        } else {
            newArgs = rebuildArgs(args.toList().toTypedArray(), "./artemis")
        }

        val builder = java.lang.ProcessBuilder(*newArgs)

        builder.directory(File(location, "bin"))

        val process = builder.start()

        val outputLogger = ProcessLogger(true, process.inputStream, logname, false)
        outputLogger.start()

        // Adding a reader to System.err, so the VM won't hang on a System.err.println as identified on this forum thread:
        val errorLogger = ProcessLogger(true, process.errorStream, logname, true)
        errorLogger.start()

        processes.add(process)

        cleanupProcess()

        return process
    }

    fun rebuildArgs(args: Array<String>, vararg prefixArgs: String): Array<String> {
        val resultArgs = arrayOfNulls<String>(args.size + prefixArgs.size)
        var i = 0
        for (arg in prefixArgs) {
            resultArgs[i++] = arg
        }
        for (arg in args) {
            resultArgs[i++] = arg
        }

        return resultArgs.filterNotNull().toTypedArray()
    }

    class ProcessLogger(val print: Boolean, val inputStream: InputStream,
                        val logName: String, val sendToErr: Boolean) : Thread() {
        init {
            isDaemon = false
        }

        override fun run() {
            try {
                val isr = InputStreamReader(inputStream)
                val br = BufferedReader(isr)

                for (line in br.lineSequence()) {
                    if (print) {
                        if (sendToErr) {
                            System.err.println(logName + "-err:" + line)
                        } else {
                            println(logName + "-out:" + line)
                        }
                    }
                }

            } catch (e: IOException) {
                // ok, stream closed
            }
        }
    }
}