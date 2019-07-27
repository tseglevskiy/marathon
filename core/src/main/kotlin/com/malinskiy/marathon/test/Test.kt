package com.malinskiy.marathon.test

import java.util.Objects

data class Test(val pkg: String,
                val clazz: String,
                val method: String,
                val metaProperties: Collection<MetaProperty>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val test = other as Test
        return pkg == test.pkg &&
                clazz == test.clazz &&
                method == test.method
    }

    override fun hashCode(): Int {
        return Objects.hash(pkg, clazz, method)
    }

    override fun toString(): String = "Test($pkg.$clazz#$method)"
}

fun Test.toTestName(): String = "$pkg.$clazz#$method"
fun Test.toSimpleSafeTestName(): String = "$clazz.$method"
fun Test.toSafeTestName(): String = "$pkg.$clazz.$method"

