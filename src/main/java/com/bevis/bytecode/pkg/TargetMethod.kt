package com.bevis.bytecode.pkg

import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import javassist.Modifier
import java.lang.StringBuilder
import kotlin.collections.ArrayList

class TargetMethod @JvmOverloads constructor(
    val className: String,
    val name: String,
    val args: List<String>?,
    val returnType: String = "",
    var fix: Boolean = false,
    val proxyMethod: String = "",
    val includePackagePath: List<String>?
) {

    var ctClass: CtClass? = null
    var ctMethod: CtMethod? = null
    var proxyCtClass: CtClass? = null
    var proxyCtMethod: CtMethod? = null
    var modifiers : Int = 0


    private var isInit = false

    fun init(classPool: ClassPool) {
        if(!isInit) {
            isInit = true
            ctClass = classPool.getOrNull(className)
                ?: throw RuntimeException("not find target class ${className}")

            if (className.isEmpty() || name.isEmpty()) {
                throw RuntimeException("scan config method name is empty")
            }
            ctMethod =
                if (args == null || args.isEmpty()) {
                    ctClass?.getDeclaredMethod(name)
                } else {
                    ctClass?.getDeclaredMethod(
                        name,
                        mutableListOf<CtClass>()
                            .apply {
                                args.forEach { clsType ->
                                    add(
                                        classPool.getOrNull(clsType)
                                            ?: throw RuntimeException("not find target method arg type $clsType")
                                    )
                                }
                            }.toTypedArray()
                    )
                } ?: throw RuntimeException("not find target method $name")

            var returnTypeCtCls: CtClass? = null
            val matchReturn =
                if (returnType == "" && CtClass.voidType == ctMethod?.returnType) {
                    returnTypeCtCls = CtClass.voidType
                    true
                } else if (returnType != "") {
                    returnTypeCtCls = classPool.getOrNull(returnType)
                    (returnTypeCtCls ?: throw RuntimeException("not find target returen type $returnType")) == ctMethod?.returnType
                } else {
                    false
                }

            if (!matchReturn) {
                throw RuntimeException("not find method $name in class $className, because return ${if (returnType == "") "void" else returnType} type does not match")
            }

            if(proxyMethod.isNotEmpty()) {
                val splitNameParts = proxyMethod.split(".")
                if(splitNameParts.isNotEmpty()) {
                    val proxyMethodName = splitNameParts[splitNameParts.size - 1]
                    val proxyClassName = proxyMethod.removeSuffix(".$proxyMethodName")

                    val callMethod = ctMethod

                    if(proxyClassName.isNotEmpty() && proxyMethodName.isNotEmpty() && callMethod != null) {
                        val proxyCtCls = classPool.getOrNull(proxyClassName) ?: throw RuntimeException("proxy method  class not find, class name is $proxyClassName")

//                        var isStaticMethod = callMethod.modifiers and Modifier.STATIC == Modifier.STATIC
//
//                        if(!isStaticMethod) {
//                            isStaticMethod = modifiers and Modifier.STATIC == Modifier.STATIC
//                        }


//                        val args = if(isStaticMethod) {
//                            arrayOf(classPool.get("java.lang.Object[]"))
//                        } else {
//                            arrayOf(classPool.get("java.lang.Object"), classPool.get("java.lang.Object[]"))
//                        }

                        val args = arrayOf(classPool.get("java.lang.Object"), classPool.get("java.lang.Object[]"))

                        val proxyCtMethod = proxyCtCls.getDeclaredMethod(proxyMethodName, args)?: throw RuntimeException("proxy method not find, method args must be java.lang.Object, java.lang.Object[]")

                        if(proxyCtCls.modifiers and Modifier.PUBLIC != Modifier.PUBLIC) {
                            throw RuntimeException("proxy method class must be public")
                        }

                        if(proxyCtCls.isArray || proxyCtCls.isAnnotation || proxyCtCls.isPrimitive
                            || proxyCtCls.isInterface || proxyCtCls.isEnum) {
                            throw RuntimeException("proxy method class must be class")
                        }

                        if(proxyCtMethod.modifiers and Modifier.PUBLIC != Modifier.PUBLIC) {
                            throw RuntimeException("proxy method must be public")
                        }

                        if(proxyCtMethod.modifiers and Modifier.STATIC != Modifier.STATIC) {
                            throw RuntimeException("proxy method must be static")
                        }

                        val proxyReturnCtCls = proxyCtMethod.returnType
                        val targetReturnCtCls = returnTypeCtCls?: CtClass.voidType

                        if(proxyReturnCtCls == targetReturnCtCls) {
                            this.proxyCtClass = proxyCtCls
                            this.proxyCtMethod = proxyCtMethod
                        } else {
                            throw RuntimeException("proxy return must be ${targetReturnCtCls.name} [current ${proxyReturnCtCls.name}]")
                        }
                    } else {
                        throw RuntimeException("proxy method config is illegal")
                    }
                }
            }
        }
    }

    fun isLegal(): Boolean {
        if (className.isEmpty()) {
            return false
        }

        if (name.isEmpty()) {
            return false
        }
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TargetMethod

        if (className != other.className) return false
        if (name != other.name) return false

        val argBuilder = StringBuilder()

        val argList = ArrayList(args?: emptyList())

        val argString = argList.let {list ->
            list.sort()
            list.forEach { arg ->
                argBuilder.append(arg).append(";")
            }
            argBuilder.toString()
        }


        val oArgBuilder = StringBuilder()

        val oArgList = ArrayList(other.args?: emptyList())

        val oArgString = oArgList.let {list ->
            list.sort()
            list.forEach { arg ->
                oArgBuilder.append(arg).append(";")
            }
            oArgBuilder.toString()
        }
        if (argString != oArgString) return false
        if (returnType != other.returnType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + name.hashCode()

        val argBuilder = StringBuilder()

        val argList = ArrayList(args?: emptyList())

        val argString = argList.let {list ->
            list.sort()
            list.forEach { arg ->
                argBuilder.append(arg).append(";")
            }
            argBuilder.toString()
        }

        result = 31 * result + argString.hashCode()
        result = 31 * result + returnType.hashCode()
        return result
    }


}