package com.bevis.bytecode.pkg

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

class ClassMethodVisitor : ClassVisitor(Opcodes.ASM5) {
    var className: String = ""
    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        className = name ?: ""
    }
}