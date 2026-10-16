package com.jingyu233.bluetoothhook.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨 Android 版本 / OEM 的反射兼容层。
 *
 * 背景：Android 17 把 `com.android.bluetooth.le_scan` 从 Java 重写成了 Kotlin，
 * 字段命名规则随之改变（去掉了 `m` 前缀）：
 *
 * | 版本          | ScanClient 里的 scanner id | ScannerApp 里的回调 |
 * |---------------|----------------------------|---------------------|
 * | Android 10~16 | `mScannerId`               | `mCallback`         |
 * | Android 17+   | `scannerId`（并新增 `app`） | `callback`          |
 *
 * 本类不硬编码任何一版的命名，而是按候选名依次探测（字段 → getter → isXxx），
 * 命中后缓存 `Field` / `Method`，避免在每次扫描回调里做高开销反射。
 *
 * 所有 "探测不到" 都不抛异常，返回 null，由调用方决定降级路径。
 */
internal object ReflectCompat {

    private val NOT_FOUND = Any()

    private val fieldCache = ConcurrentHashMap<String, Any>()
    private val methodCache = ConcurrentHashMap<String, Any>()
    private val methodListCache = ConcurrentHashMap<Class<*>, List<Method>>()

    // ------------------------------------------------------------------ 基础

    /** 类自身 + 所有父类（不含 Any / Object）。 */
    private fun classChain(type: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>(6)
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            out.add(cur)
            cur = cur.superclass
        }
        return out
    }

    private fun allMethods(type: Class<*>): List<Method> {
        val cached = methodListCache[type]
        if (cached != null) return cached
        val list = classChain(type).flatMap { c ->
            try {
                c.declaredMethods.toList()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        methodListCache[type] = list
        return list
    }

    private fun findField(type: Class<*>, name: String): Field? {
        for (c in classChain(type)) {
            try {
                val f = c.getDeclaredField(name)
                try {
                    f.isAccessible = true
                } catch (_: Throwable) {
                }
                return f
            } catch (_: Throwable) {
                // 继续往父类找
            }
        }
        return null
    }

    /** `mScannerId` → `["mScannerId", "getScannerId", "getScannerId", "isScannerId"]`（去 m 后去重）。 */
    private fun getterCandidates(name: String): List<String> {
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        val bare = if (name.length > 1 && name[0] == 'm' && name[1].isUpperCase()) {
            name.substring(1).replaceFirstChar { it.uppercaseChar() }
        } else {
            cap
        }
        return listOf(name, "get$cap", "get$bare", "is$cap", "is$bare").distinct()
    }

    // ------------------------------------------------------------ 字段 / getter

    /**
     * 按候选名依次读取字段。
     * 名字不存在、或读出来是 null，都继续试下一个候选名。
     */
    fun fieldValue(target: Any, vararg names: String): Any? {
        val cls = target::class.java
        for (name in names) {
            val key = cls.name + "#f#" + name
            val cached = fieldCache[key]
            if (cached === NOT_FOUND) continue

            val f: Field? = if (cached is Field) cached else findField(cls, name)
            if (f == null) {
                fieldCache[key] = NOT_FOUND
                continue
            }
            fieldCache[key] = f

            try {
                val v = f.get(target)
                if (v != null) return v
            } catch (_: Throwable) {
                // 读失败就换下一个候选名
            }
        }
        return null
    }

    /** 按候选名求值无参方法。 */
    fun callNoArg(target: Any, vararg names: String): Any? {
        val cls = target::class.java
        for (name in names) {
            val key = cls.name + "#m0#" + name
            val cached = methodCache[key]
            if (cached === NOT_FOUND) continue

            val m: Method = (cached as? Method) ?: run {
                val found = allMethods(cls).firstOrNull { it.name == name && it.parameterCount == 0 }
                if (found == null) {
                    methodCache[key] = NOT_FOUND
                    null
                } else {
                    try {
                        found.isAccessible = true
                    } catch (_: Throwable) {
                    }
                    methodCache[key] = found
                    found
                }
            } ?: continue

            try {
                val v = m.invoke(target)
                if (v != null) return v
            } catch (_: Throwable) {
                // 继续试下一个
            }
        }
        return null
    }

    /**
     * 字段 / getter 混合探测：Android 15 及以前走字段，Kotlin 重写后可能只剩 getter。
     */
    fun property(target: Any, vararg names: String): Any? {
        fieldValue(target, *names)?.let { return it }
        for (n in names) {
            callNoArg(target, *getterCandidates(n).toTypedArray())?.let { return it }
        }
        return null
    }

    /** [property] 并归一化成 Int（候选值可能是 Integer / Long / Short）。 */
    fun intProperty(target: Any, vararg names: String): Int? = when (val v = property(target, *names)) {
        is Int -> v
        is Number -> v.toInt()
        else -> null
    }

    // ------------------------------------------------------------ 有参方法

    private fun compatible(param: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !param.isPrimitive
        if (param.isInstance(arg)) return true

        // 数字类型互认，避免因 AIDL/源码里 int 与 long 的差异匹配失败
        if (arg is Number) {
            if (param.isPrimitive) {
                return param == Int::class.javaPrimitiveType ||
                        param == Long::class.javaPrimitiveType ||
                        param == Short::class.javaPrimitiveType ||
                        param == Byte::class.javaPrimitiveType ||
                        param == Float::class.javaPrimitiveType ||
                        param == Double::class.javaPrimitiveType
            }
            return Number::class.java.isAssignableFrom(param)
        }
        return false
    }

    /**
     * 按方法名 + 参数个数调用，参数类型做放宽匹配。
     *
     * 找不到方法时抛 [NoSuchMethodException]（调用方据此区分「没投递成功」和「投递了但返回 null」）。
     * 方法本身抛出的异常原样向上抛。
     */
    @Throws(NoSuchMethodException::class)
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val cls = target::class.java
        val key = cls.name + "#m#" + name + "/" + args.size + ":" +
                args.joinToString(",") { it?.javaClass?.name ?: "null" }

        val cached = methodCache[key]
        if (cached === NOT_FOUND) {
            throw NoSuchMethodException("${cls.name}.$name/${args.size} 不存在（已缓存未命中）")
        }

        val candidates: List<Method> = if (cached is Method) {
            listOf(cached)
        } else {
            allMethods(cls).filter { it.name == name && it.parameterCount == args.size }
        }

        if (candidates.isEmpty()) {
            methodCache[key] = NOT_FOUND
            throw NoSuchMethodException("${cls.name}.$name/${args.size} 不存在")
        }

        var lastError: Throwable? = null
        var matchedAny = false
        for (m in candidates) {
            val types = m.parameterTypes
            if (!types.indices.all { compatible(types[it], args[it]) }) continue
            matchedAny = true
            try {
                m.isAccessible = true
            } catch (_: Throwable) {
            }
            try {
                val v = m.invoke(target, *args)
                methodCache[key] = m
                return v
            } catch (t: Throwable) {
                lastError = t
            }
        }

        if (lastError != null) throw lastError
        if (!matchedAny) {
            methodCache[key] = NOT_FOUND
            throw NoSuchMethodException(
                "${cls.name}.$name 参数不匹配，实际候选=" +
                        candidates.joinToString(" | ") { m -> m.parameterTypes.joinToString(",") { it.simpleName } }
            )
        }
        return null
    }

    /** [call] 的静默版本：探测不到或调用失败一律返回 null。 */
    fun callOrNull(target: Any, name: String, vararg args: Any?): Any? = try {
        call(target, name, *args)
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ 诊断

    /** 把类的实例字段 / 非静态方法签名打成多行文本，用于投递失败时定位字段改名。 */
    fun dump(type: Class<*>): String {
        val sb = StringBuilder()
        sb.append("== dump ").append(type.name).append(" ==\n")
        sb.append("-- instance fields --\n")
        for (c in classChain(type)) {
            val fs = try {
                c.declaredFields
            } catch (_: Throwable) {
                null
            } ?: continue
            for (f in fs) {
                if (Modifier.isStatic(f.modifiers)) continue
                sb.append("  ").append(c.simpleName).append('.').append(f.name)
                    .append(" : ").append(f.type.simpleName).append('\n')
            }
        }
        sb.append("-- instance methods --\n")
        for (c in classChain(type)) {
            val ms = try {
                c.declaredMethods
            } catch (_: Throwable) {
                null
            } ?: continue
            for (m in ms) {
                if (Modifier.isStatic(m.modifiers)) continue
                sb.append("  ").append(c.simpleName).append('.').append(m.name)
                    .append('(').append(m.parameterTypes.joinToString(", ") { it.simpleName }).append(")\n")
            }
        }
        return sb.toString()
    }
}