package com.malinskiy.marathon.analytics.tracker.local

import com.malinskiy.marathon.analytics.tracker.NoOpTracker
import com.malinskiy.marathon.device.DeviceInfo
import com.malinskiy.marathon.device.DevicePoolId
import com.malinskiy.marathon.execution.TestResult
import com.malinskiy.marathon.report.internal.TestResultRepo

internal class TestResultsTracker(private val testResultRepo: TestResultRepo) : NoOpTracker() {

    override fun trackTestFinished(poolId: DevicePoolId, device: DeviceInfo, testResult: TestResult) {
        testResultRepo.testFinished(poolId, device, testResult)
    }
}
