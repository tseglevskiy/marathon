package com.malinskiy.marathon.report

import com.malinskiy.marathon.analytics.Analytics
import com.malinskiy.marathon.device.*
import com.malinskiy.marathon.execution.TestResult
import com.malinskiy.marathon.execution.TestStatus
import com.malinskiy.marathon.io.FileManager
import com.malinskiy.marathon.log.MarathonLogging
import com.malinskiy.marathon.report.junit.JUnitReporter
import com.malinskiy.marathon.test.Test

class IgnoredTestsReporter(val fm: FileManager, val analytics: Analytics) {
    val logger = MarathonLogging.logger(IgnoredTestsReporter::class.java.simpleName)

    private val junitReporter: JUnitReporter = JUnitReporter(fm)

    val fakeDevicePoolId = DevicePoolId("ignored")

    val fakeDevice = DeviceInfo(operatingSystem = OperatingSystem("Fake OS"),
            serialNumber = "fake serial",
            model = "fake model",
            manufacturer = "fake manufacturer",
            networkState = NetworkState.CONNECTED,
            deviceFeatures = emptyList(),
            healthy = true)

    init {
        analytics.trackDeviceConnected(fakeDevicePoolId, fakeDevice)
    }

    fun reportTest(test: Test) {


        logger.warn { "HAPPY gonna report ignored $test" }
        val tr = TestResult(test = test,
                device = fakeDevice,
                status = TestStatus.IGNORED,
                startTime = 0,
                endTime = 0)

//        junitReporter.testFinished(fakeDevicePoolId, fakeDevice, tr)

    analytics.trackRawTestRun(fakeDevicePoolId, fakeDevice, tr)
    analytics.trackTestFinished(fakeDevicePoolId, fakeDevice, tr)

    }
}