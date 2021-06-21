package com.bevis.bytecode.pkg

import java.lang.RuntimeException

/**
 * 主要处理通配符逻辑
 * * : 匹配一组字符中多个连续字符， 0 - n 个连续字符
 * ** : 匹配多组字符，包括 .  0 - n 个连续字符
 * ? : 匹配一组中，单个字符，不包括匹配 . 0 - 1 个字符
 *
 * 通用字符内容指 [A-Za-z0-9\$\_]*
 */
object SymbolParser {

    /**
     * 解析描述为正则规则
     */
    fun parseToRegex(description: String?): Regex {
        if(description == null ||description.isEmpty()) {
            throw RuntimeException("description format fail ")
        }
        val group = description.split(".")

        // 校验 ** 规则
        if(group.isNotEmpty()) {
            group.forEach {item ->
                if(item.contains("**") && item != "**") {
                    throw RuntimeException("** format fail , for detail >> $item")
                }
            }
        } else if(description.contains("**") && description != "**") {
            throw RuntimeException("** format fail , for detail >> $description")
        }

        val replaceDescription = description
            .replace(".", "\\.")
            .replace("**", "[A-Za-z0-9\\\$\\_\\.]*") // 优先匹配 **
            .replace("*", "[A-Za-z0-9\\\$\\_]*")

        return Regex(replaceDescription)
    }
}
