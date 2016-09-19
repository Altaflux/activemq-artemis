package org.apache.activemq.artemis.cli.commands

import io.airlift.airline.Option
import java.io.File


abstract class ActionAbstract : Action {

    @Option(name = arrayOf("--verbose"), description = "Adds more information on the execution")
    override var verbose: Boolean = false

    override var brokerInstance: String? = null
        get() {
            if (field == null) {
                var value = System.getProperty("artemis.instance")
                value?.let {
                    value = value.replace("\\", "/")
                    System.setProperty("artemis.instance", value)
                }
                field = value
            }
            return field
        }

    override var brokerHome: String? = null
        get() {
            if (field == null) {
                field = if (System.getProperty("artemis.home") != null) {
                    field = System.getProperty("artemis.home").replace("\\", "/")
                    System.setProperty("artemis.home", field)
                    field
                } else {
                    "."
                }
            }
            return field
        }

    protected lateinit var context: ActionContext

    //    @Override
//    public void setHomeValues(File brokerHome, File brokerInstance) {
//        if (brokerHome != null) {
//            this.brokerHome = brokerHome.getAbsolutePath();
//        }
//        if (brokerInstance != null) {
//            this.brokerInstance = brokerInstance.getAbsolutePath();
//        }
//    }
    override fun setHomeValues(brokerHome: File?, brokerInstance: File?) {
        brokerHome?.let { this.brokerHome = brokerHome.absolutePath }
        brokerInstance?.let { this.brokerInstance = brokerInstance.absolutePath }
    }

    override fun execute(context: ActionContext): Any? {
        this.context = context
        return null
    }
}