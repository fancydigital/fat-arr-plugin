package com.bevis.bytecode.pkg

import java.io.File
import java.io.FileOutputStream

class Reporter(private val configList: Set<String>) {
    companion object {
        const val TYPE_MAIN = "main"
        const val TYPE_ERROR = "error"
        const val TYPE_INVALID_INVOKE= "invalidInvoke"
        const val TYPE_SCAN_CLASS = "scanClass"
        const val TYPE_SCAN_JAR = "scanJar"
    }
    private val mMainReport = mutableListOf<String>()
    private val mAllClassesReport = mutableListOf<String>()
    private val mErrorReport = mutableListOf<ReportError>()
    private val mJarClassesReport = mutableListOf<String>()
    private val mInvalidInvokeReport = mutableListOf<String>()

    fun clear() {
        mMainReport.clear()
        mAllClassesReport.clear()
        mErrorReport.clear()
        mJarClassesReport.clear()
    }

    fun addMissReferenceReportMessage(message: String) {
        mInvalidInvokeReport.add(message)
    }

    fun addJarScanReportMessage(message: String) {
        mJarClassesReport.add(message)
    }

    fun addSuccessMessage(message: String) {
        mMainReport.add(message)
    }

    fun addScanClassMessage(clsName: String) {
        mAllClassesReport.add(clsName)
    }

    fun addErrorMessage(
        cacheKey: String? = null,
                    className:String? = null,
                    methodName:String? = null,
                    message: String? = null,
                    error: Exception?) {
        mErrorReport.add(ReportError(cacheKey, className, methodName, message, error))
    }



    fun writeFile(dir: File) {
        val logDir = File(dir, "scan_methods_reports")
        if(logDir.exists()) {
            logDir.deleteRecursively()
        }
        logDir.mkdir()

        if(mMainReport.isNotEmpty() && configList.contains(TYPE_MAIN)) {
            writeContent(logDir, "main_report.log", mMainReport)
        }

        if(mAllClassesReport.isNotEmpty() && configList.contains(TYPE_SCAN_CLASS)) {
            writeContent(logDir, "all_classes_report.log", mAllClassesReport)
        }

        if(mErrorReport.isNotEmpty() && configList.contains(TYPE_ERROR)) {
            writeContent(logDir, "error_report.log", mErrorReport)
        }

        if(mJarClassesReport.isNotEmpty() && configList.contains(TYPE_SCAN_JAR)) {
            writeContent(logDir, "all_jar_classes_report.log", mJarClassesReport)
        }

        if(mInvalidInvokeReport.isNotEmpty() && configList.contains(TYPE_INVALID_INVOKE)) {
            writeContent(logDir, "invalid_invoke_report.log", mInvalidInvokeReport)
        }
    }

    private fun writeContent(dir: File, fileName: String, list: Collection<Any>) {
        val fileOutput = FileOutputStream(File(dir, fileName))
        list.forEach { content ->
            fileOutput.write("$content\n".toByteArray())
        }
    }

    private data class ReportError(
        val cacheKey: String?,
        val className:String?,
        val methodName:String?,
        val message: String?,
        val error: Exception?)
}