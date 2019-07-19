package com.malinskiy.marathon.execution.device

import com.malinskiy.marathon.actor.Actor
import com.malinskiy.marathon.actor.StateMachine
import com.malinskiy.marathon.device.Device
import com.malinskiy.marathon.device.DevicePoolId
import com.malinskiy.marathon.exceptions.DeviceLostException
import com.malinskiy.marathon.exceptions.DeviceTimeoutException
import com.malinskiy.marathon.exceptions.TestBatchExecutionException
import com.malinskiy.marathon.execution.Configuration
import com.malinskiy.marathon.execution.DevicePoolMessage
import com.malinskiy.marathon.execution.DevicePoolMessage.FromDevice.IsReady
import com.malinskiy.marathon.execution.TestBatchResults
import com.malinskiy.marathon.execution.progress.ProgressReporter
import com.malinskiy.marathon.execution.withRetry
import com.malinskiy.marathon.log.MarathonLogging
import com.malinskiy.marathon.test.TestBatch
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates

class DeviceActor(private val devicePoolId: DevicePoolId,
                  private val pool: SendChannel<DevicePoolMessage>,
                  private val configuration: Configuration,
                  val device: Device,
                  private val progressReporter: ProgressReporter,
                  parent: Job,
                  context: CoroutineContext) :
        Actor<DeviceEvent>(parent = parent, context = context) {

    val lock = Object()

    private val state = StateMachine.create<DeviceState, DeviceEvent, DeviceAction> {
        initialState(DeviceState.Connected)
        state<DeviceState.Connected> {
            on<DeviceEvent.Initialize> {
                transitionTo(DeviceState.Initializing, DeviceAction.Initialize)
            }
            on<DeviceEvent.Terminate> {
                transitionTo(DeviceState.Terminated, DeviceAction.Terminate())
            }
            on<DeviceEvent.WakeUp> {
                dontTransition()
            }
        }
        state<DeviceState.Initializing> {
            on<DeviceEvent.InitializingComplete> {
                transitionTo(DeviceState.Ready, DeviceAction.NotifyIsReady())
            }
            on<DeviceEvent.Terminate> {
                transitionTo(DeviceState.Terminated, DeviceAction.Terminate())
            }
            on<DeviceEvent.WakeUp> {
                dontTransition()
            }
        }
        state<DeviceState.Ready> {
            on<DeviceEvent.Execute> {
                val deferred = CompletableDeferred<TestBatchResults>()
                transitionTo(DeviceState.Running(it.batch, deferred), DeviceAction.ExecuteBatch(it.batch, deferred))
            }
            on<DeviceEvent.WakeUp> {
                transitionTo(DeviceState.Ready, DeviceAction.NotifyIsReady())
            }
            on<DeviceEvent.Terminate> {
                transitionTo(DeviceState.Terminated, DeviceAction.Terminate())
            }
        }
        state<DeviceState.Running> {
            on<DeviceEvent.Terminate> {
                transitionTo(DeviceState.Terminated, DeviceAction.Terminate(testBatch))
            }
            on<DeviceEvent.RunningComplete> {
                transitionTo(DeviceState.Ready, DeviceAction.NotifyIsReady(this.result))
            }
            on<DeviceEvent.Initialize> {
                transitionTo(DeviceState.Initializing, DeviceAction.Initialize)
            }
            on<DeviceEvent.WakeUp> {
                dontTransition()
            }
        }
        state<DeviceState.Terminated> {
            on<DeviceEvent.WakeUp> {
                dontTransition()
            }
        }
        onTransition {

            val validTransition = it as? StateMachine.Transition.Valid
            if (validTransition !is StateMachine.Transition.Valid) {
                logger.error { "Invalid transition from ${it.fromState} event ${it.event}" }
                logger.error { "HAPPY Invalid transition from ${it.fromState} event ${it.event}" }
                return@onTransition
            }

            logger.warn("HAPPY ${device.serialNumber} transition from ${validTransition.fromState} to ${validTransition.toState} by event ${validTransition.event}")

            val sideEffect = validTransition.sideEffect
            when (sideEffect) {
                DeviceAction.Initialize -> {
                    initialize()
                }
                is DeviceAction.NotifyIsReady -> {
                    sideEffect.result?.let {
                        sendResults(it)
                    }
                    notifyIsReady()
                }
                is DeviceAction.ExecuteBatch -> {
                    executeBatch(sideEffect.batch, sideEffect.result)
                }
                is DeviceAction.Terminate -> {
                    val batch = sideEffect.batch
                    if (batch == null) {
                        terminate()
                    } else {
                        returnBatch(batch).invokeOnCompletion {
                            terminate()
                        }
                    }
                }
                null -> {
                    // do nothing
                }
            }
        }
    }

    private val logger = MarathonLogging.logger("DevicePool[${devicePoolId.name}]_DeviceActor[${device.serialNumber}]")

    val isAvailable: Boolean
        get() {
            return !isClosedForSend && state.state == DeviceState.Ready
        }

    override suspend fun receive(msg: DeviceEvent) {
        when (msg) {
            is DeviceEvent.GetDeviceState -> {
                msg.deferred.complete(state.state)
            }
            else -> {
                state.transition(msg)
            }
        }
    }

    private fun sendResults(result: CompletableDeferred<TestBatchResults>) {
        launch {
            val testResults = result.await()
            pool.send(DevicePoolMessage.FromDevice.CompletedTestBatch(device, testResults))
        }
    }

    private fun notifyIsReady() {
        launch {
            pool.send(IsReady(device))
        }
    }

    private var job by Delegates.observable<Job?>(null) { _, _, newValue ->
        newValue?.invokeOnCompletion {
            if (it != null) {
                logger.error(it) { "Error ${it.message}" }
                state.transition(DeviceEvent.Terminate)
                terminate()
            }
        }
    }

    private fun initialize() {
        logger.debug { "initialize ${device.serialNumber}" }
        job = async {
            try {
                withRetry(30, 10000) {
                    if (isActive) {
                        try {
                            logger.warn { "HAPPY gonna prepare ${device.serialNumber}" }

                            device.prepare(configuration)
                            logger.warn { "HAPPY prepared ${device.serialNumber}" }
                        } catch (e: Exception) {
                            logger.warn { "HAPPY another shit happens ${device.serialNumber} ${e}" }
                            logger.debug { "device ${device.serialNumber} initialization failed. Retrying" }
                            throw e
                        }
                    } else {
                        logger.warn { "HAPPY inactive ${device.serialNumber}" }

                    }
                }
            } catch (e: Exception) {
                logger.warn { "HAPPY shit happens ${device.serialNumber} ${e}" }

                state.transition(DeviceEvent.Terminate)
            }

            state.transition(DeviceEvent.InitializingComplete)
        }
    }

    private fun executeBatch(batch: TestBatch, result: CompletableDeferred<TestBatchResults>) {
        logger.debug { "executeBatch ${device.serialNumber}" }
        job = async {
            try {
                logger.warn("HAPPY ${device.serialNumber} batch started")
                device.execute(configuration, devicePoolId, batch, result, progressReporter)
                logger.warn("HAPPY ${device.serialNumber} batch passed")
            } catch (e: DeviceLostException) {
                logger.warn("HAPPY ${device.serialNumber} DeviceLostException")
                logger.error(e) { "Critical error during execution" }
                returnBatch(batch)
                state.transition(DeviceEvent.Terminate)
            } catch (e: DeviceTimeoutException) {
                logger.warn("HAPPY ${device.serialNumber} DeviceTimeoutException")
                logger.error(e) { "Device got stuck" }
                returnBatch(batch)
                state.transition(DeviceEvent.Initialize)
            } catch (e: TestBatchExecutionException) {
                logger.warn("HAPPY ${device.serialNumber} TestBatchExecutionException")
                returnBatch(batch)
            }

            state.transition(DeviceEvent.RunningComplete)
        }
    }

    private fun returnBatch(batch: TestBatch): Job {
        return launch {
            pool.send(DevicePoolMessage.FromDevice.ReturnTestBatch(device, batch))
        }
    }

    private fun terminate() {
        logger.debug { "terminate ${device.serialNumber}" }
        job?.cancel()
        close()
    }
}

