package com.malinskiy.marathon.android.executor.listeners

import com.android.ddmlib.testrunner.TestIdentifier
import com.android.ddmlib.testrunner.TestRunResult as DdmLibTestRunResult

abstract class AbstractTestRunResultListener : NoOpTestRunListener() {

    private val runResult: DdmLibTestRunResult = DdmLibTestRunResult()
    private var active = true

    override fun testRunStarted(runName: String, testCount: Int) {
        synchronized(active) {
            if (active) runResult.testRunStarted(runName, testCount)
        }

    }

    override fun testStarted(test: TestIdentifier) {
        synchronized(active) {
            if (active) runResult.testStarted(test)
        }
    }

    override fun testFailed(test: TestIdentifier, trace: String) {
        synchronized(active) {
            if (active) runResult.testFailed(test, trace)
        }
    }

    override fun testAssumptionFailure(test: TestIdentifier, trace: String) {
        synchronized(active) {
            if (active) runResult.testAssumptionFailure(test, trace)
        }
    }

    override fun testIgnored(test: TestIdentifier) {
        synchronized(active) {
            if (active) runResult.testIgnored(test)
        }
    }

    override fun testEnded(test: TestIdentifier, testMetrics: Map<String, String>) {
        synchronized(active) {
            if (active) runResult.testEnded(test, testMetrics)
        }
    }

    override fun testRunFailed(errorMessage: String) {
        synchronized(active) {
            if (active) runResult.testRunFailed(errorMessage)
        }
    }

    override fun testRunStopped(elapsedTime: Long) {
        synchronized(active) {
            if (active) runResult.testRunStopped(elapsedTime)
        }
    }

    override fun testRunEnded(elapsedTime: Long, runMetrics: Map<String, String>) {
        synchronized(active) {
            if (active) {
                runResult.testRunEnded(elapsedTime, runMetrics)
                handleTestRunResults(runResult)
            }
        }
    }

    abstract fun handleTestRunResults(runResult: DdmLibTestRunResult)

    fun forceEnd() {
        synchronized(active) {
            if (active) {
                active = false
                handleTestRunResults(runResult)
            }
        }
    }
}
