package com.malinskiy.marathon.test

import com.malinskiy.marathon.execution.Configuration

data class TestBatch(val tests: List<Test>)

fun TestBatch.calculateTimeout(configuration: Configuration): Long =
        Math.min(configuration.testOutputTimeoutMillis * tests.size,
                    configuration.testBatchTimeoutMillis)
