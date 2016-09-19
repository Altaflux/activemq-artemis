package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Option
import java.util.*


open class InputAbstract : ActionAbstract() {

    @Option(name = arrayOf("--silent"), description = "It will disable all the inputs, and it would make a best guess for any required input")
    private var silentInput = false

    private lateinit var scanner: Scanner


    protected fun input(propertyName: String, prompt: String, silentDefault: String): String {
        if (silentInput) {
            return silentDefault
        }
        var inputStr: String
        var valid = false
        println()
        do {
            context.out.println(propertyName + ": mandatory:")
            context.out.println(prompt)
            inputStr = scanner.nextLine()
            if (inputStr.trim { it <= ' ' } == "") {
                println("Invalid Entry!")
            } else {
                valid = true
            }
        } while (!valid)

        return inputStr.trim { it <= ' ' }
    }

    protected fun inputPassword(propertyName: String, prompt: String, silentDefault: String): String {
        if (silentInput) {
            return silentDefault
        }

        var inputStr: String
        var valid = false
        println()
        do {
            context.out.println(propertyName + ": is mandatory with this configuration:")
            context.out.println(prompt)
            inputStr = String(System.console().readPassword())

            if (inputStr.trim { it <= ' ' } == "") {
                println("Invalid Entry!")
            } else {
                valid = true
            }
        } while (!valid)

        return inputStr.trim { it <= ' ' }
    }

    override fun execute(context: ActionContext): Any? {
        super.execute(context)
        scanner = Scanner(context.`in`)
        return null
    }
}