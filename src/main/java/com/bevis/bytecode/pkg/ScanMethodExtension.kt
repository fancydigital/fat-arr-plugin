package com.bevis.bytecode.pkg

import groovy.lang.Closure
import javassist.Modifier

interface EnableCallback {
    fun onCallback(extension: ScanMethodExtension , enable: Boolean)
}

class ScanMethodExtension(private val enableCb: EnableCallback? = null) {
    var enable: Boolean = false
        set(value) {
            field = value
            enableCb?.onCallback(this@ScanMethodExtension, field)
        }
    var configs: List<Map<String, Any?>> = mutableListOf()
    var fixConfigs: List<LinkedHashMap<String, Any?>> = mutableListOf()
    var reportDir: String = ""
    var reportType: Set<String> = mutableSetOf(Reporter.TYPE_MAIN)
    var scanWhiteList: List<Map<String, Any?>> = mutableListOf()

    val scanWhiteListMethods: Set<TargetMethod>
        get() {
            val list = mutableSetOf<TargetMethod>()
            scanWhiteList.forEach { config ->
                val targetMethod = resolveConfig(config)
                if (targetMethod.isLegal()) {
                    list.add(targetMethod)
                }
            }
            return list
        }

    val allTargetMethods: Set<TargetMethod>
        get() {
            val list = mutableSetOf<TargetMethod>()
            configs.forEach { config ->
                val targetMethod = resolveConfig(config)
                if (targetMethod.isLegal()) {
                    list.add(targetMethod)
                }
            }


            fixConfigs.forEach { config ->
                val targetMethod = resolveConfig(config, true)
                if (targetMethod.isLegal()) {
                    list.remove(targetMethod)
                    list.add(targetMethod)
                }
            }

            return list
        }

    fun setPrint(msg: String) {
        println("ttttt >>> $msg")
    }

    fun setSymbol(closure: Closure<Void>) {
        closure.delegate = this
        closure.call()
    }

    companion object {
        fun resolveConfig(config: Map<String, Any?>, fix : Boolean = false):TargetMethod {
            val args = config["args"]?.toString()?.split(";")?.filter { it != "" } ?: emptyList()
            val includePkgPaths = config["includePackagePaths"]?: emptyList<String>()
            val proxyMethod =  config["proxyMethod"]?.toString()?:""
            val targetMethod = TargetMethod(
                config["className"]?.toString() ?: "",
                config["methodName"]?.toString() ?: "",
                args,
                config["return"]?.toString() ?: "",
                if(fix || proxyMethod.isNotEmpty()){true} else {config["fix"]?.toString() == "true"},
                proxyMethod,
                    includePkgPaths as List<String>
            )


            if(config["isStatic"]?.toString() == "true"){
                targetMethod.modifiers = Modifier.STATIC or targetMethod.modifiers
            }

            return targetMethod
        }
    }

}