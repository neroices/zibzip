package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.OpState
import dev.neroices.zibzip.domain.ProgressListener
import dev.neroices.zibzip.domain.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CancelTokenContext(val token: Long) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<CancelTokenContext>
}

sealed interface OpResult {
    data class Success(val message: String) : OpResult
    data class Failure(val message: String) : OpResult
    data object Cancelled : OpResult
}

private class RunningTask(
    val id: String,
    val title: String,
    val token: Long,
    val job: Job,
)

/**
 * Runs long-running I/O and archive operations concurrently on an application-wide scope.
 * Tracks per-task progress, real-time transfer speed (MB/s), percentage, and manages cancellation.
 */
class OperationRunner(private val scope: CoroutineScope) {

    private val _tasks = MutableStateFlow<List<OpState.Running>>(emptyList())
    val tasks: StateFlow<List<OpState.Running>> = _tasks.asStateFlow()

    /** Primary/latest operation state, kept for single-state consumers and backwards compatibility. */
    val state: StateFlow<OpState> = _tasks.map { list ->
        list.lastOrNull() ?: OpState.Idle
    }.stateIn(scope, SharingStarted.Eagerly, OpState.Idle)

    private val _results = MutableSharedFlow<OpResult>(extraBufferCapacity = 32)
    val results: SharedFlow<OpResult> = _results.asSharedFlow()

    private val activeTasks = ConcurrentHashMap<String, RunningTask>()

    val isBusy: Boolean get() = _tasks.value.isNotEmpty()

    /** Maximum concurrent operations allowed to prevent excessive thread/file descriptor exhaustion. */
    val maxConcurrent: Int = 8

    /**
     * Starts a new concurrent operation.
     * Returns false if the maximum concurrent operations limit is reached.
     */
    fun start(
        title: String,
        keepScreenOn: Boolean = false,
        trackSpeed: Boolean = true,
        block: suspend (ProgressListener) -> String,
    ): Boolean {
        if (_tasks.value.size >= maxConcurrent) return false

        val taskId = UUID.randomUUID().toString()
        val token = NativeArchiveBridge.nativeCreateCancelToken()
        val initialTask = OpState.Running(title = title, id = taskId, keepScreenOn = keepScreenOn)

        _tasks.update { it + initialTask }

        val job = scope.launch(Dispatchers.IO + CancelTokenContext(token), start = CoroutineStart.LAZY) {
            AppLogger.log("Task started: $title ($taskId)")
            var lastSpeedTime = 0L
            var lastSpeedDone = 0L
            var currentSpeed = 0L
            var lastEmitTime = 0L

            var result: OpResult = OpResult.Cancelled
            try {
                val listener = ProgressListener { done, total, current ->
                    val now = System.currentTimeMillis()
                    if (trackSpeed) {
                        if (lastSpeedTime == 0L) {
                            lastSpeedTime = now
                            lastSpeedDone = done
                        } else {
                            val deltaSpeedTime = now - lastSpeedTime
                            if (deltaSpeedTime >= 250) {
                                val deltaBytes = (done - lastSpeedDone).coerceAtLeast(0)
                                currentSpeed = (deltaBytes * 1000) / deltaSpeedTime
                                lastSpeedTime = now
                                lastSpeedDone = done
                            }
                        }
                    }

                    val isFinished = total > 0 && done >= total
                    if (isFinished || now - lastEmitTime >= 100) {
                        lastEmitTime = now
                        _tasks.update { list ->
                            list.map { item ->
                                if (item.id == taskId) {
                                    item.copy(
                                        done = done,
                                        total = total,
                                        current = current,
                                        speedBytesPerSec = currentSpeed,
                                    )
                                } else {
                                    item
                                }
                            }
                        }
                    }
                }
                val message = block(listener)
                AppLogger.log("Task completed: $title")
                result = OpResult.Success(message)
            } catch (e: CancellationException) {
                AppLogger.log("Task cancelled: $title")
                result = OpResult.Cancelled
            } catch (t: Throwable) {
                val currentJob = coroutineContext[Job]
                val isCancelled = currentJob?.isCancelled == true ||
                    activeTasks[taskId]?.job?.isCancelled == true ||
                    t is CancellationException ||
                    t.message?.contains("cancelled", ignoreCase = true) == true
                result = if (isCancelled) {
                    AppLogger.log("Task cancelled: $title")
                    OpResult.Cancelled
                } else {
                    AppLogger.log("Task failed: $title - ${t.message}", t)
                    OpResult.Failure(t.userMessage())
                }
            } finally {
                activeTasks.remove(taskId)
                NativeArchiveBridge.nativeFreeCancelToken(token)
                _tasks.update { list -> list.filterNot { it.id == taskId } }
                _results.tryEmit(result)
            }
        }

        activeTasks[taskId] = RunningTask(taskId, title, token, job)
        job.start()
        return true
    }

    /** Triggers cancellation of a specific task by id. */
    fun cancel(taskId: String) {
        val task = activeTasks[taskId] ?: return
        NativeArchiveBridge.nativeCancelOperation(task.token)
        task.job.cancel()
    }

    /** Cancels all running tasks. */
    fun cancelAll() {
        val snapshot = activeTasks.values.toList()
        for (task in snapshot) {
            NativeArchiveBridge.nativeCancelOperation(task.token)
            task.job.cancel()
        }
    }

    /** Cancels the most recently added running task, or all if none specified. */
    fun cancel() {
        val last = _tasks.value.lastOrNull()
        if (last != null) {
            cancel(last.id)
        } else {
            cancelAll()
        }
    }
}
