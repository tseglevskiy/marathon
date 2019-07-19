package com.malinskiy.marathon

import com.google.gson.Gson
import com.malinskiy.marathon.analytics.Analytics
import com.malinskiy.marathon.analytics.AnalyticsFactory
import com.malinskiy.marathon.config.LogicalConfigurationValidator
import com.malinskiy.marathon.device.DeviceProvider
import com.malinskiy.marathon.exceptions.NoDevicesException
import com.malinskiy.marathon.execution.Configuration
import com.malinskiy.marathon.execution.Scheduler
import com.malinskiy.marathon.execution.TestParser
import com.malinskiy.marathon.execution.TestShard
import com.malinskiy.marathon.execution.progress.ProgressReporter
import com.malinskiy.marathon.io.FileManager
import com.malinskiy.marathon.log.MarathonLogging
import com.malinskiy.marathon.report.CompositeSummaryPrinter
import com.malinskiy.marathon.report.Summary
import com.malinskiy.marathon.report.SummaryCompiler
import com.malinskiy.marathon.report.SummaryPrinter
import com.malinskiy.marathon.report.debug.timeline.TimelineSummaryPrinter
import com.malinskiy.marathon.report.debug.timeline.TimelineSummaryProvider
import com.malinskiy.marathon.report.html.HtmlSummaryPrinter
import com.malinskiy.marathon.report.internal.DeviceInfoReporter
import com.malinskiy.marathon.report.internal.TestResultReporter
import com.malinskiy.marathon.test.MetaProperty
import com.malinskiy.marathon.test.Test
import com.malinskiy.marathon.test.toTestName
import com.malinskiy.marathon.usageanalytics.TrackActionType
import com.malinskiy.marathon.usageanalytics.UsageAnalytics
import com.malinskiy.marathon.usageanalytics.tracker.Event
import com.malinskiy.marathon.vendor.VendorConfiguration
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private val log = MarathonLogging.logger {}

val JUNIT_IGNORE_META_PROPERY = MetaProperty("org.junit.Ignore")

class Marathon(val configuration: Configuration) {

    private val fileManager = FileManager(configuration.outputDir)
    private val gson = Gson()

    private val testResultReporter = TestResultReporter(fileManager, gson)
    private val deviceInfoReporter = DeviceInfoReporter(fileManager, gson)
    private val analyticsFactory = AnalyticsFactory(configuration, fileManager, deviceInfoReporter, testResultReporter,
            gson)
    private val configurationValidator = LogicalConfigurationValidator()

    private fun configureLogging(vendorConfiguration: VendorConfiguration) {
        MarathonLogging.debug = configuration.debug

        vendorConfiguration.logConfigurator()?.configure(vendorConfiguration)
    }

    private val summaryCompiler = SummaryCompiler(deviceInfoReporter, testResultReporter, configuration)

    private fun loadSummaryPrinter(): SummaryPrinter {
        val outputDir = configuration.outputDir
        val htmlSummaryPrinter = HtmlSummaryPrinter(gson, outputDir)
        if (configuration.debug) {
            return CompositeSummaryPrinter(listOf(
                    htmlSummaryPrinter,
                    TimelineSummaryPrinter(TimelineSummaryProvider(analyticsFactory.rawTestResultTracker), gson, outputDir)
            ))
        }
        return htmlSummaryPrinter
    }

    private suspend fun loadDeviceProvider(vendorConfiguration: VendorConfiguration): DeviceProvider {
        val vendorDeviceProvider = vendorConfiguration.deviceProvider()
                ?: ServiceLoader.load(DeviceProvider::class.java).first()

        vendorDeviceProvider.initialize(configuration.vendorConfiguration)
        return vendorDeviceProvider
    }

    private fun loadTestParser(vendorConfiguration: VendorConfiguration): TestParser {
        val vendorTestParser = vendorConfiguration.testParser()
        if (vendorTestParser != null) {
            return vendorTestParser
        }
        val loader = ServiceLoader.load(TestParser::class.java)
        return loader.first()
    }

    fun run() = runBlocking {
        try {
            runAsync()
        } catch (th: Throwable) {
            log.error(th.toString())

            when(th) {
                is NoDevicesException -> {
                    log.warn { "No devices found" }
                    false
                }
                else -> configuration.ignoreFailures
            }
        }
    }

    suspend fun runAsync(): Boolean {
        configureLogging(configuration.vendorConfiguration)
        trackAnalytics(configuration)

        val testParser = loadTestParser(configuration.vendorConfiguration)
        val deviceProvider = loadDeviceProvider(configuration.vendorConfiguration)
        val analytics = analyticsFactory.create()

        configurationValidator.validate(configuration)

        val parsedTests = testParser.extract(configuration)
        val tests = applyTestFilters(parsedTests)
        val shard = prepareTestShard(tests, analytics)

        log.info("Scheduling ${tests.size} tests")
        log.debug(tests.joinToString(", ") { it.toTestName() })
        val progressReporter = ProgressReporter()
        val currentCoroutineContext = coroutineContext
        val scheduler = Scheduler(deviceProvider, analytics, configuration, shard, progressReporter, currentCoroutineContext)

        if (configuration.outputDir.exists()) {
            log.info { "Output ${configuration.outputDir} already exists" }
            configuration.outputDir.deleteRecursively()
        }
        configuration.outputDir.mkdirs()

        val getElapsedTimeMillis: () -> Long = {
            val startTime = System.currentTimeMillis()
            fun(): Long { return System.currentTimeMillis() - startTime }
        }()

        val hook = installShutdownHook(scheduler, getElapsedTimeMillis)

        if (tests.isNotEmpty()) {
            scheduler.execute()
        }

        printSummary(hook, scheduler, getElapsedTimeMillis)

        onFinish(analytics, deviceProvider)
        return progressReporter.aggregateResult()
    }

    private fun printSummary(shutdownHook: ShutdownHook, scheduler: Scheduler, elapsedTime: () -> Long) {
        if (shutdownHook.uninstall()) {
            printSummary(scheduler, elapsedTime())
        }
    }

    private fun installShutdownHook(scheduler: Scheduler, elapsedTime: () -> Long): ShutdownHook {
        val shutdownHook = ShutdownHook(configuration) { printSummary(scheduler, elapsedTime()) }
        shutdownHook.install()
        return shutdownHook
    }

    private suspend fun onFinish(analytics: Analytics, deviceProvider: DeviceProvider) {
        analytics.terminate()
        analytics.close()
        deviceProvider.terminate()
    }

    private fun printSummary(scheduler: Scheduler, executionTime: Long) {
        val pools = scheduler.getPools()
        if (!pools.isEmpty()) {
            val summaryPrinter = loadSummaryPrinter()
            val summary = summaryCompiler.compile(scheduler.getPools())
            printCliReport(summary, executionTime)
            summaryPrinter.print(summary)
        }
    }

    private fun applyTestFilters(parsedTests: List<Test>): List<Test> {
        var tests = parsedTests.filter { test ->
            configuration.testClassRegexes.all { it.matches(test.clazz) }
        }
        configuration.filteringConfiguration.whitelist.forEach { tests = it.filter(tests) }
        configuration.filteringConfiguration.blacklist.forEach { tests = it.filterNot(tests) }
        tests = tests.filter { !it.metaProperties.contains(JUNIT_IGNORE_META_PROPERY) }
        return tests
    }

    private fun prepareTestShard(tests: List<Test>, analytics: Analytics): TestShard {
        val shardingStrategy = configuration.shardingStrategy
        val flakinessShard = configuration.flakinessStrategy
        val shard = shardingStrategy.createShard(tests)
        return flakinessShard.process(shard, analytics)
    }

    private fun trackAnalytics(configuration: Configuration) {
        UsageAnalytics.tracker.run {
            trackEvent(Event(TrackActionType.VendorConfiguration, configuration.vendorConfiguration.javaClass.name))
            trackEvent(Event(TrackActionType.PoolingStrategy, configuration.poolingStrategy.javaClass.name))
            trackEvent(Event(TrackActionType.ShardingStrategy, configuration.shardingStrategy.javaClass.name))
            trackEvent(Event(TrackActionType.SortingStrategy, configuration.sortingStrategy.javaClass.name))
            trackEvent(Event(TrackActionType.RetryStrategy, configuration.retryStrategy.javaClass.name))
            trackEvent(Event(TrackActionType.BatchingStrategy, configuration.batchingStrategy.javaClass.name))
            trackEvent(Event(TrackActionType.FlakinessStrategy, configuration.flakinessStrategy.javaClass.name))
        }
    }

    private fun printCliReport(summary: Summary, executionTime: Long) {
        val cliReportBuilder = StringBuilder().appendln("Marathon run passed:")
        summary.pools.forEach {
            cliReportBuilder.appendln("Device pool ${it.poolId.name}: ${it.passed} passed, ${it.failed} failed, ${it.ignored} ignored tests")
        }

        val hours = TimeUnit.MILLISECONDS.toHours(executionTime)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(executionTime) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(executionTime) % 60
        cliReportBuilder.appendln("Total time: ${hours}H ${minutes}m ${seconds}s")

        println(cliReportBuilder)
    }
}
