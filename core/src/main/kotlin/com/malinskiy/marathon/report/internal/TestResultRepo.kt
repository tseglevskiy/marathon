package com.malinskiy.marathon.report.internal

import com.google.gson.Gson
import com.malinskiy.marathon.device.DeviceInfo
import com.malinskiy.marathon.device.DevicePoolId
import com.malinskiy.marathon.execution.TestResult
import com.malinskiy.marathon.io.FileManager
import com.malinskiy.marathon.io.FileType
import java.io.FileReader

class TestResultRepo(private val fileManager: FileManager,
                     private val gson: Gson) {

    fun testFinished(poolId: DevicePoolId, device: DeviceInfo, testResult: TestResult) {
        val file = fileManager.createFile(FileType.TEST_RESULT, poolId, device, testResult.test)
        file.writeText(gson.toJson(testResult))
    }

    fun readTests(poolId: DevicePoolId, device: DeviceInfo): List<TestResult> {
        return fileManager.getTestResultFilesForDevice(poolId, device.serialNumber).map {
            gson.fromJson(FileReader(it), TestResult::class.java)
        }
    }
}
