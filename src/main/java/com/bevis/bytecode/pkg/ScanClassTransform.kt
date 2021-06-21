package com.bevis.bytecode.pkg

import com.android.build.api.transform.*
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.utils.FileUtils
import javassist.*
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import org.apache.commons.io.IOUtils
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.io.File
import java.util.*
import java.util.jar.JarOutputStream

class ScanClassTransform(
    private val project: Project,
    private val scanMethodExtension: ScanMethodExtension,
    private val androidExtension: AppExtension
) : Transform() {
    private val mReporter = Reporter(scanMethodExtension.reportType)

    override fun getName(): String = "ScanBytecodeTask"

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> =
        TransformManager.CONTENT_CLASS

    override fun isIncremental(): Boolean = false

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> =
        TransformManager.SCOPE_FULL_PROJECT

    override fun transform(transformInvocation: TransformInvocation?) {
        super.transform(transformInvocation)

        mReporter.clear()

        val allOutputFiles = mutableMapOf<QualifiedContent, File>()
        val allClassLocationMap = mutableMapOf<String, QualifiedContent>()
        val allClassLocationMap2  = mutableMapOf<QualifiedContent, MutableList<String>>()


        val invalidInvokeCache =  mutableMapOf<String, MutableSet<String>>()
        val scanCache = mutableMapOf<String, MutableSet<MethodCall>?>()
        val fixMethodCache = mutableMapOf<QualifiedContent, MutableMap<CtClass, MutableList<CtBehavior>>>()

        val whiteMethods = mutableListOf<CtMethod>()

        transformInvocation?.inputs?.forEach { transformInput ->
            transformInput.directoryInputs.forEach { dirInput ->
                allOutputFiles[dirInput] = dirInput.file
            }

            transformInput.jarInputs.forEach { jarInput ->
                allOutputFiles[jarInput] = jarInput.file
            }
        }

        val targetMethods = scanMethodExtension.allTargetMethods

        val fixWhiteMethods = scanMethodExtension.scanWhiteListMethods

        try {
            val classPool = ClassPool()

            // 加载所有Class内容
            allClassLocationMap2.putAll(resolveFileContents(classPool, allOutputFiles))

            allClassLocationMap.putAll(allClassLocationMap2.let {
                val tmpMap = mutableMapOf<String, QualifiedContent>()

                it.keys.forEach { content ->
                    it[content]?.forEach { className ->
                        tmpMap[className] = content
                    }
                }

                tmpMap
            })

            targetMethods.forEach { targetMethod ->
                targetMethod.init(classPool)
                if(targetMethod.proxyCtMethod != null) {
                    whiteMethods.add(targetMethod.proxyCtMethod!!)
                }
            }

            fixWhiteMethods.forEach {targetMethods ->
                targetMethods.init(classPool)
                if(targetMethods.ctMethod != null) {
                    whiteMethods.add(targetMethods.ctMethod!!)
                }
            }

            // 修复缓存信息，避免多次修复
            val fixSignatureCache = mutableSetOf<String>()
            allClassLocationMap.forEach { (currentClassName, currentContent) ->
                val currentCtCls = classPool.getOrNull(currentClassName)

                if (currentCtCls != null && !currentCtCls.isInterface && !currentCtCls.isPrimitive && !currentCtCls.isAnnotation && !currentCtCls.isArray) {
                    currentCtCls.declaredBehaviors.forEach { whereCtBehavior ->

                        if (!whiteMethods.contains(whereCtBehavior)) {

                            if (targetMethods.isEmpty()) {
                                whereCtBehavior.instrument(object : ExprEditor() {
                                    override fun edit(invokeMethod: MethodCall?) {
                                        if (invokeMethod != null && !assertMethodCallLegal(
                                                invokeMethod
                                            )
                                        ) {
                                            val signature =
                                                "${invokeMethod.className}.${invokeMethod.methodName}${invokeMethod.signature}"
                                            if (invalidInvokeCache[signature] == null) {
                                                invalidInvokeCache[signature] =
                                                    mutableSetOf()
                                            }
                                            invalidInvokeCache[signature]?.add("${invokeMethod.where().longName}(${invokeMethod.fileName}:${invokeMethod.lineNumber})")
                                        }
                                    }
                                })
                            } else {

                                for(targetMethod in targetMethods) {

                                    val includePkgPaths = targetMethod.includePackagePath
                                    var isIncludePkgPaths = true
                                    val curPkgPath = whereCtBehavior.declaringClass.name
                                    if(includePkgPaths != null && includePkgPaths.isNotEmpty()) {
                                        isIncludePkgPaths = false
                                        for(includePkgPath in includePkgPaths) {
                                            if(curPkgPath.startsWith(includePkgPath)) {
                                                isIncludePkgPaths = true
                                                break
                                            }
                                        }
                                    }

                                    if(!isIncludePkgPaths) {
                                        break
                                    }

                                    targetMethod.init(classPool)

                                    whereCtBehavior.instrument(object : ExprEditor() {
                                        override fun edit(invokeMethod: MethodCall?) {
                                            super.edit(invokeMethod)

                                            if (invokeMethod != null) {
                                                // 采集非法调用的情况
                                                val isInvokeMethodLegal =
                                                        assertMethodCallLegal(invokeMethod)
                                                if (!isInvokeMethodLegal) {
                                                    val signature =
                                                            "${invokeMethod.className}.${invokeMethod.methodName}${invokeMethod.signature}"
                                                    if (invalidInvokeCache[signature] == null) {
                                                        invalidInvokeCache[signature] =
                                                                mutableSetOf()
                                                    }
                                                    invalidInvokeCache[signature]?.add("${invokeMethod.where().longName}(${invokeMethod.fileName}:${invokeMethod.lineNumber})")
                                                } else {
                                                    val invokeMethodName: String? =
                                                            invokeMethod.method?.name
                                                    val invokeMethodCtCls: CtClass? =
                                                            invokeMethod.method?.declaringClass
                                                    if (invokeMethodName != null && invokeMethodName.isNotEmpty() && invokeMethodCtCls != null) {

                                                        val cacheKey =
                                                                "${if (targetMethod.returnType.trim() == "") "void" else targetMethod.returnType} ${targetMethod.className}.${targetMethod.name}(${targetMethod.args?.let {
                                                                    val sb = StringBuilder()
                                                                    it.forEach { arg ->
                                                                        if (sb.isNotEmpty()) {
                                                                            sb.append(", ")
                                                                        }
                                                                        if (arg.trim() != "") {
                                                                            sb.append(arg)
                                                                        }
                                                                    }
                                                                    sb.toString()
                                                                } ?: ""})"

                                                        val isTargetMethod = assertTargetMethod(
                                                                cacheKey,
                                                                invokeMethodName,
                                                                invokeMethodCtCls,
                                                                invokeMethod,
                                                                targetMethod,
                                                                classPool,
                                                                targetMethod.includePackagePath?: emptyList()
                                                        )

                                                        if (isTargetMethod) {
                                                            var reportCaches =
                                                                    scanCache[cacheKey]
                                                            if (reportCaches == null) {
                                                                reportCaches = mutableSetOf()
                                                                scanCache[cacheKey] =
                                                                        reportCaches
                                                            }
                                                            reportCaches.add(invokeMethod)
                                                        }

                                                        if (isTargetMethod && targetMethod.fix) {
                                                            val signature =
                                                                    countFixCallMethodSignature(
                                                                            targetMethod,
                                                                            whereCtBehavior,
                                                                            invokeMethod
                                                                    )
                                                            if (!fixSignatureCache.contains(
                                                                            signature
                                                                    ) && fixCallMethod(
                                                                            targetMethod,
                                                                            whereCtBehavior,
                                                                            invokeMethod,
                                                                            classPool
                                                                    )
                                                            ) {
                                                                fixSignatureCache.add(signature)
                                                                // 添加到 修复内容中
                                                                var fixClassCache =
                                                                        fixMethodCache[currentContent]
                                                                if (fixClassCache == null) {
                                                                    fixClassCache =
                                                                            mutableMapOf()
                                                                    fixMethodCache[currentContent] =
                                                                            fixClassCache
                                                                }
                                                                var fixBehaviorList =
                                                                        fixClassCache[whereCtBehavior.declaringClass]
                                                                if (fixBehaviorList == null) {
                                                                    fixBehaviorList =
                                                                            mutableListOf()
                                                                    fixClassCache[whereCtBehavior.declaringClass] =
                                                                            fixBehaviorList
                                                                }
                                                                fixBehaviorList.add(whereCtBehavior)
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                        }
                                    })
                                }
                            }
                        } else {
                            println("[ScanMethods] skip method >>  ${whereCtBehavior.longName}")
                        }
                    }
                }
            }

            // 创建修复输出
            val outputClasses = mutableListOf<CtClass>()
            val outputMethods = mutableListOf<CtBehavior>()
            val fixOutputContents = createOutputContents(fixMethodCache, outputClasses, outputMethods)

            // 替换已经生效的内容
            fixOutputContents.forEach { (content, file) ->
                allOutputFiles[content] = file
            }

            scanCache.forEach {
                mReporter.addSuccessMessage("METHOD >> ${it.key}:")

                it.value?.forEach {call ->
                    val success = outputMethods.find { ctBehavior ->
                        "${ctBehavior.longName}${ctBehavior.signature}" == "${call.where().longName}${call.where().signature}"
                    } != null

                    val message = "${String.format(
                        "%7s",
                        if (outputMethods.isNotEmpty()) " [Fix " + if (success) "√]" else "×]" else ""
                    )}     at ${call.where().longName}${if(call.where() is CtMethod) ":${(call.where() as CtMethod).returnType.name}" else ""}(${call.fileName}:${call.lineNumber})"


                    mReporter.addSuccessMessage(message)
                }
                mReporter.addSuccessMessage("\n")
                mReporter.addSuccessMessage("\n")
            }

            invalidInvokeCache.forEach { (signature, referencePlaces) ->
                mReporter.addMissReferenceReportMessage("METHOD >> $signature:")
                referencePlaces.forEach { referencePlace ->
                    mReporter.addMissReferenceReportMessage("            at $referencePlace")
                }
                mReporter.addMissReferenceReportMessage("\n")
                mReporter.addMissReferenceReportMessage("\n")
            }
            if (scanMethodExtension.reportDir.isNotEmpty()) {
                val dir = File(scanMethodExtension.reportDir)
                if (!dir.exists()) {
                    dir.mkdir()
                } else if (!dir.isDirectory) {
                    throw RuntimeException("${dir.absolutePath} must require be dir type")
                }
                mReporter.writeFile(dir)
            }
        } catch (error: Exception) {
            error.printStackTrace()
            println(error)
        }

        allOutputFiles.forEach { (content, outputFile) ->
            if (content is DirectoryInput) {
                val outputDesc = transformInvocation?.outputProvider?.getContentLocation(
                    content.name,
                    content.contentTypes,
                    content.scopes,
                    Format.DIRECTORY
                )

                if (outputDesc != null) {
                    FileUtils.copyDirectory(
                        outputFile,
                        outputDesc
                    )
                }
            } else if (content is JarInput) {
                val output = transformInvocation?.outputProvider?.getContentLocation(
                    content.name,
                    content.contentTypes,
                    content.scopes,
                    Format.JAR
                )
                if (output != null) {
                    FileUtils.copyFile(
                        outputFile,
                        output
                    )
                }
            }
        }

    }

    private fun assertMethodCallLegal(methodCall: MethodCall): Boolean {
        try {
            methodCall.method?.name
            methodCall.method?.declaringClass
            return true
        } catch (e: Exception) {
        }
        return false
    }

    private fun resolveFileContents(classPool: ClassPool, classContents: Map<QualifiedContent, File>):Map<QualifiedContent, MutableList<String>> {//Map<String, QualifiedContent> {
        classPool.appendSystemPath()


        androidExtension.bootClasspath.forEach { file ->
            classPool.appendClassPath(file.absolutePath)
        }

        val classMap = mutableMapOf<String, QualifiedContent>()

        val classMap2 = mutableMapOf<QualifiedContent, MutableList<String>>()

        classContents.keys.forEach { content ->

            if (content is DirectoryInput) {
                mReporter.addScanClassMessage("${content.file.absolutePath}：")

                val classFiles = project.fileTree(content.file).filter { file ->
                    file.name.endsWith(".class")
                }.toList()

                if (classFiles.isNotEmpty()) {
                    classFiles.forEach { classFile ->
                        val clsStream = classFile.inputStream()
                        val clsName = getClassName(clsStream)
                        classMap[clsName] = content


                        (classMap2[content]?: mutableListOf()).apply {
                            classMap2[content] = this
                            add(clsName)
                        }


                        classPool.appendClassPath(
                            ByteArrayClassPath(
                                clsName,
                                IOUtils.toByteArray(classFile.inputStream())
                            )
                        )
                        mReporter.addScanClassMessage("    $clsName")
                    }
                }
                mReporter.addScanClassMessage("\n")
                mReporter.addScanClassMessage("\n")
            } else if (content is JarInput) {
                classPool.appendClassPath(content.file.absolutePath)
                val clsNames = getClassNames(content)
                mReporter.addScanClassMessage("${content.file.absolutePath}：")

                clsNames.forEach { clsName ->
                    classMap[clsName] = content

                    (classMap2[content]?: mutableListOf()).apply {
                        classMap2[content] = this
                        add(clsName)
                    }

                    mReporter.addScanClassMessage("    $clsName")

                }
                mReporter.addScanClassMessage("\n")
                mReporter.addScanClassMessage("\n")
            }
        }
        return classMap2
    }

    /**
     * 判断是否为目标方法
     */
    private fun assertTargetMethod(
        cacheKey: String,
        callMethodName: String,
        callMethodCtCls: CtClass,
        invokeMethod: MethodCall,
        targetMethod: TargetMethod,
        classPool: ClassPool,
        includeScopePkgPaths: List<String>
    ): Boolean {
        if (callMethodName.isNotEmpty()) {
            try {

                if ((targetMethod.ctClass?.subclassOf(callMethodCtCls) == true || targetMethod.ctClass == callMethodCtCls) &&
                    callMethodName == targetMethod.ctMethod?.name
                ) {
                    val scanMethodArgSize =
                        targetMethod.args?.size ?: 0
                    val callMethodArgSize =
                        invokeMethod.method?.parameterTypes?.size ?: 0

                    var argIndex = 0

                    var assertArgs =
                        scanMethodArgSize == callMethodArgSize
                    val assertReturn =
                        if (targetMethod.returnType == "" && CtClass.voidType == invokeMethod.method.returnType) {
                            true
                        } else if (targetMethod.returnType != "") {
                            (classPool.getOrNull(
                                targetMethod.returnType
                            )
                                ?: throw RuntimeException("not find target returen type ${targetMethod.returnType}")) == invokeMethod.method.returnType
                        } else {
                            false
                        }


                    invokeMethod.method.parameterTypes?.forEach { argCtCls ->
                        if (assertArgs) {
                            if (targetMethod.args?.get(
                                    argIndex
                                ) != null
                            ) {
                                val scanArgCtCls =
                                    classPool.get(
                                        targetMethod.args[argIndex]
                                    )
                                        ?: throw RuntimeException(
                                            "not find target method arg type ${targetMethod.args[argIndex]}"
                                        )
                                if (scanArgCtCls != argCtCls) {
                                    assertArgs = false
                                    return@forEach
                                }
                            }
                        } else {
                            return@forEach
                        }
                        argIndex++
                    }

                    if (assertReturn && assertArgs) {
                        return true
                    }
                }
            } catch (e: Exception) {
                mReporter.addErrorMessage(cacheKey, invokeMethod.className, invokeMethod.methodName,
                    invokeMethod.where().longName, e)
                e.printStackTrace()
            }
        }
        return false
    }

    private fun createOutputContents(
        inputContent: Map<QualifiedContent, MutableMap<CtClass, MutableList<CtBehavior>>>,
        outputFixCls: MutableList<CtClass>,
        outputFixBehavior: MutableList<CtBehavior>
    ):MutableMap<QualifiedContent, File> {

        val outputContents = mutableMapOf<QualifiedContent, File>()

        val tmpDir = File("${project.buildDir.absolutePath}/intermediates/scan-method-fix/")
        tmpDir.deleteOnExit()
        tmpDir.mkdir()


        val fixClassDir = File("${tmpDir.absolutePath}/dir")
        val fixJarDir = File("${tmpDir.absolutePath}/jar")

        inputContent.forEach { (fixContent, fixClassMap) ->
            if (fixContent is DirectoryInput) {
                fixClassDir.deleteOnExit()
                fixClassDir.mkdir()

                // 复制内容
                FileUtils.copyDirectory(
                    fixContent.file,
                    fixClassDir
                )

                fixClassMap.forEach { (ctClass, _) ->
                    val newOutputFile = File(
                        "${fixClassDir.absolutePath}/${ctClass.name.replace(
                            '.',
                            '/'
                        )}.class"
                    )

                    println("from ${fixContent.file.absolutePath}  删除已有的内容 ${newOutputFile.absolutePath}")
                    // 删除已有的类
                    newOutputFile.deleteOnExit()

                    ctClass.writeFile(fixClassDir.absolutePath)

                    if (!newOutputFile.exists()) {
                        mReporter.addErrorMessage(
                            "",
                            ctClass.name,
                            "",
                            "fix result fail, file not exist ${newOutputFile.absolutePath}",
                            null
                        )
                    }
                    outputFixCls.add(ctClass)
                    fixClassMap[ctClass]?.asIterable()?.forEach { fixBehavior ->
                        outputFixBehavior.add(fixBehavior)
                    }
                    outputContents[fixContent] = fixClassDir
                }
            } else if (fixContent is JarInput) {
                fixJarDir.deleteOnExit()
                fixJarDir.mkdir()

                val tmpJarDir = File("${fixJarDir.absolutePath}/${UUID.randomUUID()}")
                tmpJarDir.mkdir()

                val jarOutputFile = File("$tmpJarDir/${fixContent.file.name}.opt")
                val jarOutput = JarOutputStream(jarOutputFile.outputStream())

                val jarFile = JarFile(fixContent.file)
                val enumeration = jarFile.entries()

                val ctClassIndex = mutableMapOf<String, CtClass>().apply {
                    fixClassMap.forEach { (ctClass, _) ->
                        put(ctClass.name, ctClass)
                    }
                }

                while (enumeration.hasMoreElements()) {
                    val jarEntry = enumeration.nextElement()
                    var handle = false
                    if (jarEntry != null && jarEntry.name.endsWith(".class")) {
                        val clsName = jarEntry.name.replace('/', '.').replace(".class", "")
                        val cacheCls = ctClassIndex[clsName]
                        if (cacheCls != null) {
                            val byte = cacheCls.toBytecode()
                            jarOutput.putNextEntry(JarEntry(jarEntry.name))
                            jarOutput.write(byte)
                            outputFixCls.add(cacheCls)
                            fixClassMap[cacheCls]?.asIterable()?.forEach { fixBehavior ->
                                outputFixBehavior.add(fixBehavior)
                            }
                            handle = true
                        }
                    }
                    if (!handle) {
                        jarOutput.putNextEntry(jarEntry)
                        jarOutput.write(IOUtils.toByteArray(jarFile.getInputStream(jarEntry)))
                    }
                    jarOutput.closeEntry()
                }

                val newJarFile = File("$tmpJarDir/${fixContent.file.name}")
                jarOutputFile.renameTo(newJarFile)
                jarOutput.close()
                outputContents[fixContent] = newJarFile
            }
        }
        return outputContents
    }

    /**
     * 计算修复指纹
     */
    private fun countFixCallMethodSignature(targetMethod: TargetMethod, whereBehavior: CtBehavior, callMethod: MethodCall): String {
        return if (targetMethod.proxyCtMethod != null && targetMethod.proxyCtClass != null) {
            "${whereBehavior.longName}${whereBehavior.signature}${callMethod.lineNumber}"

        } else {
            "${whereBehavior.longName}${whereBehavior.signature}"
        }

    }

    private fun fixCallMethod(
        targetMethod: TargetMethod,
        whereBehavior: CtBehavior, // 所查询的行为（方法、构造）,并且所需要 try - catch 的方法
        callMethod: MethodCall, // 所调用的方法
        classPool: ClassPool // pool 上下文
    ): Boolean {
        var hasFix = false
        try {

            // 计算默认返回值
            var defReturnValue: String? = null
            if (whereBehavior.methodInfo.isMethod) {
                if (defReturnValue == null) {
                    val declareMethod =
                        whereBehavior.declaringClass.getDeclaredMethod(
                            whereBehavior.name
                        )
                    defReturnValue =
                        when (declareMethod.returnType) {
                            CtClass.voidType -> null
                            CtClass.booleanType -> "false"
                            CtClass.byteType,
                            CtClass.charType,
                            CtClass.doubleType,
                            CtClass.floatType,
                            CtClass.intType,
                            CtClass.longType,
                            CtClass.shortType -> "0"
                            else -> "null"
                        }
                }
            }


            if (!hasFix) {
                // 有替代要求，则进行替代，如果没办法进行替代，则进行普通 try-catch 处理
                val proxyCtMethod = targetMethod.proxyCtMethod
                val proxyCtClass = targetMethod.proxyCtClass

                if (proxyCtMethod != null && proxyCtClass != null) {
                    var isStaticMethod = callMethod.method.modifiers and Modifier.STATIC == Modifier.STATIC

                    if(!isStaticMethod) {
                        isStaticMethod = targetMethod.modifiers and Modifier.STATIC == Modifier.STATIC
                    }

                    callMethod.replace("{\$_ =  ${proxyCtClass.name}.${proxyCtMethod.name}(${if(isStaticMethod){"null,"} else {"\$0,"}}\$args);}")
                } else {
                    // 一般情况下 将整个方法 try-catch 即可
                    whereBehavior.addCatch(
                        "{ ${if (defReturnValue == null) "return;" else "return $defReturnValue;"} }",
                        classPool.get("java.lang.Exception")
                    )
                }

                // 一般情况下 将整个方法 try-catch 即可
//                whereBehavior.addCatch(
//                    "{ ${if (defReturnValue == null) "return;" else "return $defReturnValue;"} }",
//                    classPool.get("java.lang.Exception")
//                )
                hasFix = true

            }
        } catch (ignore: Exception) {
            var callMethodName: String? = null
            try {
                callMethodName = callMethod.method?.name
            } catch (ignore2: Exception) {
            }

            ignore.printStackTrace()
            mReporter.addErrorMessage(
                null,
                null,
                null,
                "${whereBehavior.longName} call ${callMethodName?:""}  ( add catch block fail, ${ignore} ) ",
                null
            )
        }
        return hasFix
    }


    private fun getClassName(inputStream: InputStream): String {
        val classReader = ClassReader(inputStream)
        val visitor = ClassMethodVisitor()
        classReader.accept(
            visitor,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        )
        return visitor.className.replace('/', '.')
    }

    private fun getClassNames(jarInput: JarInput): Set<String> {
        val classNames = mutableSetOf<String>()
        val jarFile = JarFile(jarInput.file)

        val jarInputStream = JarInputStream(jarInput.file.inputStream())

        var jarEntry: JarEntry?
        mReporter.addJarScanReportMessage("${jarInput.file.absolutePath} : ")

        while (jarInputStream.nextJarEntry.apply {
                jarEntry = this
            } != null) {
            mReporter.addJarScanReportMessage("    ${jarEntry?.name}")
            if (jarEntry?.name?.endsWith(".class") == true && jarEntry?.name?.contains("module-info") == false) {
                val jarStream = jarFile.getInputStream(jarEntry)
                val className = getClassName(jarStream)
                classNames.add(className)
            }

        }
        mReporter.addJarScanReportMessage("\n")
        mReporter.addJarScanReportMessage("\n")
        return classNames
    }
}