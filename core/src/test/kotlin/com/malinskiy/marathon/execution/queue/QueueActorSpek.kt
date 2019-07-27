package com.malinskiy.marathon.execution.queue

import com.malinskiy.marathon.analytics.Analytics
import com.malinskiy.marathon.device.DevicePoolId
import com.malinskiy.marathon.device.DeviceStub
import com.malinskiy.marathon.device.toDeviceInfo
import com.malinskiy.marathon.execution.Configuration
import com.malinskiy.marathon.execution.DevicePoolMessage.FromQueue
import com.malinskiy.marathon.execution.DevicePoolMessage.FromQueue.ExecuteBatch
import com.malinskiy.marathon.execution.TestBatchResults
import com.malinskiy.marathon.execution.TestResult
import com.malinskiy.marathon.execution.TestShard
import com.malinskiy.marathon.execution.TestStatus
import com.malinskiy.marathon.execution.strategy.impl.batching.FixedSizeBatchingStrategy
import com.malinskiy.marathon.test.Test
import com.malinskiy.marathon.test.TestVendorConfiguration
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldContainSame
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import java.io.File

class QueueActorSpek : Spek({
    describe("queue actor") {
        val job by memoized { Job() }
        val poolChannel by memoized { Channel<FromQueue>() }
        val analytics by memoized { mock<Analytics>() }

        given("incomplete tests retry quota is 0, max batch size is 1 and one test in the shard") {
            val actor by memoized {
                createQueueActor(
                        configuration = DEFAULT_CONFIGURATION.copy(
                                uncompletedTestRetryQuota = 0,
                                batchingStrategy = FixedSizeBatchingStrategy(size = 1)
                        ),
                        tests = listOf(TEST_1),
                        poolChannel = poolChannel,
                        analytics = analytics,
                        job = job
                )
            }

            describe("requesting batch and handling completed batch") {
                beforeEachTest {
                    runBlocking {
                        actor.send(QueueMessage.RequestBatch(TEST_DEVICE_INFO))
                        poolChannel.receive()
                    }
                }

                describe("received incomplete test once") {
                    beforeEachTest {
                        val results = createBatchResult(uncompleted = listOf(
                                createTestResult(TEST_1, TestStatus.FAILURE)
                        ))
                        runBlocking {
                            actor.send(QueueMessage.Completed(TEST_DEVICE_INFO, results))
                        }
                    }

                    it("should have empty queue") {
                        val isEmptyDeferred = CompletableDeferred<Boolean>()
                        runBlocking {
                            actor.send(QueueMessage.IsEmpty(isEmptyDeferred))
                            isEmptyDeferred.await() shouldBe true
                        }
                    }

                    it("should report test as failed") {
                        runBlocking {
                            val captor = argumentCaptor<TestResult>()
                            verify(analytics).trackTestFinished(any(), any(), captor.capture())
                            captor.firstValue.test shouldBe TEST_1
                            captor.firstValue.status shouldBe TestStatus.FAILURE
                        }
                    }
                }
            }
        }

        given("incomplete tests retry quota is 1, max batch size is 1 and one test in the shard") {
            val actor by memoized {
                createQueueActor(
                        configuration = DEFAULT_CONFIGURATION.copy(
                                uncompletedTestRetryQuota = 1,
                                batchingStrategy = FixedSizeBatchingStrategy(size = 1)
                        ),
                        tests = listOf(TEST_1),
                        poolChannel = poolChannel,
                        analytics = analytics,
                        job = job
                )
            }

            describe("requesting batch and handling completed batch") {
                beforeEachTest {
                    runBlocking {
                        actor.send(QueueMessage.RequestBatch(TEST_DEVICE_INFO))
                        poolChannel.receive()
                    }
                }

                describe("received incomplete test first time") {
                    beforeEachTest {
                        val results = createBatchResult(uncompleted = listOf(
                                createTestResult(TEST_1, TestStatus.FAILURE)
                        ))
                        runBlocking {
                            actor.send(QueueMessage.Completed(TEST_DEVICE_INFO, results))
                        }
                    }

                    it("should have not empty queue") {
                        val isEmptyDeferred = CompletableDeferred<Boolean>()
                        runBlocking {
                            actor.send(QueueMessage.IsEmpty(isEmptyDeferred))
                            isEmptyDeferred.await() shouldBe false
                        }
                    }

                    it("should not report any test finishes") {
                        runBlocking {
                            verify(analytics, never()).trackTestFinished(any(), any(), any())
                        }
                    }

                    it("should provide incomplete test in the batch") {
                        runBlocking {
                            actor.send(QueueMessage.RequestBatch(TEST_DEVICE_INFO))
                            val response = poolChannel.receive()
                            response::class shouldBe ExecuteBatch::class
                            (response as ExecuteBatch).batch.tests shouldContainSame listOf(TEST_1)
                        }
                    }

                    describe("received incomplete test second time") {
                        beforeEachTest {
                            runBlocking {
                                actor.send(QueueMessage.RequestBatch(TEST_DEVICE_INFO))
                                poolChannel.receive()
                            }

                            val results = createBatchResult(uncompleted = listOf(
                                    createTestResult(TEST_1, TestStatus.FAILURE)
                            ))
                            runBlocking {
                                actor.send(QueueMessage.Completed(TEST_DEVICE_INFO, results))
                            }
                        }

                        it("should have empty queue") {
                            val isEmptyDeferred = CompletableDeferred<Boolean>()
                            runBlocking {
                                actor.send(QueueMessage.IsEmpty(isEmptyDeferred))
                                isEmptyDeferred.await() shouldBe true
                            }
                        }

                        it("should report test as failed") {
                            runBlocking {
                                val captor = argumentCaptor<TestResult>()
                                verify(analytics).trackTestFinished(any(), any(), captor.capture())
                                captor.firstValue.test shouldBe TEST_1
                                captor.firstValue.status shouldBe TestStatus.FAILURE
                            }
                        }
                    }
                }
            }
        }

        afterEachTest {
            job.cancel()
        }
    }
})

private val TEST_DEVICE = DeviceStub()
private val TEST_DEVICE_INFO = TEST_DEVICE.toDeviceInfo()

private val TEST_1 = Test("", "", "test1", emptyList())

private fun createBatchResult(finished: List<TestResult> = emptyList(),
                              failed: List<TestResult> = emptyList(),
                              uncompleted: List<TestResult> = emptyList()): TestBatchResults = TestBatchResults(
        TEST_DEVICE,
        finished,
        failed,
        uncompleted,
        emptyList()
)

private fun createTestResult(test: Test, status: TestStatus) = TestResult(
        test = test,
        device = TEST_DEVICE_INFO,
        status = status,
        startTime = 0,
        endTime = 0,
        stacktrace = null,
        attachments = emptyList()
)

private fun createQueueActor(configuration: Configuration,
                             tests: List<Test>,
                             poolChannel: SendChannel<FromQueue>,
                             analytics: Analytics,
                             job: Job) = QueueActor(
        configuration,
        TestShard(tests, emptyList()),
        analytics,
        poolChannel,
        DevicePoolId("test"),
        mock(),
        job,
        Dispatchers.Unconfined
)

private val DEFAULT_CONFIGURATION = Configuration(
        name = "",
        outputDir = File(""),
        analyticsConfiguration = null,
        poolingStrategy = null,
        shardingStrategy = null,
        sortingStrategy = null,
        batchingStrategy = null,
        flakinessStrategy = null,
        retryStrategy = null,
        filteringConfiguration = null,
        ignoreFailures = null,
        isCodeCoverageEnabled = null,
        fallbackToScreenshots = null,
        strictMode = null,
        uncompletedTestRetryQuota = null,
        testClassRegexes = null,
        includeSerialRegexes = null,
        excludeSerialRegexes = null,
        testBatchTimeoutMillis = null,
        testOutputTimeoutMillis = null,
        debug = null,
        vendorConfiguration = TestVendorConfiguration(
                testParser = mock(),
                deviceProvider = mock()
        ),
        analyticsTracking = false
)
