package com.bevis.bytecode.pkg

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ScanClassPlugin : Plugin<Project>, EnableCallback {
    private lateinit var project: Project
    private  var hasRegister = false

    override fun apply(project: Project) {
        this.project = project
        // 必须显示配置 enable
        val pluginExtension = ScanMethodExtension(this)
        project.extensions.add("scanMethods", pluginExtension)

        println("apply scan class plugin")

        project.gradle.startParameter.taskNames.forEach { startTask ->
            // 如果只是想扫描下 启动 scan 任务即可
            if (startTask.startsWith("scanMethods")) {
                pluginExtension.enable = true
                return@forEach
            }
        }
    }


    override fun onCallback(extension: ScanMethodExtension, enable: Boolean) {
        if(enable && !hasRegister) {
            hasRegister = true
            project.plugins.findPlugin("com.android.application")?.also {
                println("type >> ${project.extensions.findByName("android")?.javaClass?.name}")

                val appExtension = project.extensions.findByName("android") as? AppExtension
                appExtension ?: throw RuntimeException()
                appExtension.registerTransform(
                    ScanClassTransform(
                        project,
                        extension,
                        appExtension
                    )
                )


                project.afterEvaluate {
                    appExtension.applicationVariants.forEach { variantTask ->
                        project.tasks.create("scanMethodsFor${variantTask.name.let { variantName ->
                            var newVariantName = variantName
                            if (variantName.isNotEmpty()) {
                                newVariantName = variantName.replaceRange(
                                    0,
                                    1,
                                    variantName[0].toUpperCase().toString()
                                )
                            }
                            newVariantName
                        }}") {
                            it.group = "sui"
                            it.dependsOn(
                                project.tasks.getByName("clean"),
                                variantTask.assemble
                            )

                        }
                    }
                }
            }
        }
    }
}

