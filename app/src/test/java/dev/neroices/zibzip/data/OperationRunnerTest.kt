package dev.neroices.zibzip.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationRunnerTest {

    @Test
    fun allowsMultipleConcurrentOperations() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = OperationRunner(testScope)

        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()

        val started1 = runner.start("Task 1") {
            gate1.await()
            "Done 1"
        }
        val started2 = runner.start("Task 2") {
            gate2.await()
            "Done 2"
        }

        assertTrue("First operation should start", started1)
        assertTrue("Second operation should start concurrently", started2)

        // Wait until both tasks are active in runner
        withTimeout(3000) {
            while (runner.tasks.value.size < 2) {
                delay(10)
            }
        }

        assertEquals(2, runner.tasks.value.size)
        assertTrue(runner.isBusy)

        val tasks = runner.tasks.value
        val task1 = tasks.first { it.title == "Task 1" }
        val task2 = tasks.first { it.title == "Task 2" }

        assertTrue(task1.id.isNotEmpty())
        assertTrue(task2.id.isNotEmpty())

        gate1.complete(Unit)
        gate2.complete(Unit)

        withTimeout(3000) {
            while (runner.tasks.value.isNotEmpty()) {
                delay(10)
            }
        }

        assertEquals(0, runner.tasks.value.size)
        assertFalse(runner.isBusy)
    }

    @Test
    fun cancelSpecificTask_cancelsOnlyThatTask() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = OperationRunner(testScope)

        val gate2 = CompletableDeferred<Unit>()

        runner.start("Task 1") {
            delay(10_000)
            "Done 1"
        }
        runner.start("Task 2") {
            gate2.await()
            "Done 2"
        }

        withTimeout(3000) {
            while (runner.tasks.value.size < 2) {
                delay(10)
            }
        }

        val task1 = runner.tasks.value.first { it.title == "Task 1" }
        runner.cancel(task1.id)

        // Task 1 should be removed, Task 2 still running
        withTimeout(3000) {
            while (runner.tasks.value.any { it.id == task1.id }) {
                delay(10)
            }
        }

        assertEquals(1, runner.tasks.value.size)
        assertEquals("Task 2", runner.tasks.value.first().title)

        gate2.complete(Unit)

        withTimeout(3000) {
            while (runner.tasks.value.isNotEmpty()) {
                delay(10)
            }
        }

        assertEquals(0, runner.tasks.value.size)
    }

    @Test
    fun cancelTask_emitsCancelledResult() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = OperationRunner(testScope)

        val subscribed = CompletableDeferred<Unit>()
        val resultDeferred = CompletableDeferred<OpResult>()
        val collectJob = testScope.launch {
            runner.results
                .onSubscription { subscribed.complete(Unit) }
                .collect { resultDeferred.complete(it) }
        }

        subscribed.await()

        runner.start("Cancellable") {
            delay(10_000)
            "Done"
        }

        withTimeout(3000) {
            while (runner.tasks.value.isEmpty()) {
                delay(10)
            }
        }

        val task = runner.tasks.value.first()
        runner.cancel(task.id)

        val result = withTimeout(3000) { resultDeferred.await() }
        assertEquals(OpResult.Cancelled, result)
        collectJob.cancel()
    }

    @Test
    fun tracksKeepScreenOnFlag(): Unit = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = OperationRunner(testScope)

        val gate = CompletableDeferred<Unit>()
        val started = runner.start("Keep Awake Task", keepScreenOn = true) {
            gate.await()
            "Done"
        }
        assertTrue(started)

        withTimeout(3000) {
            while (runner.tasks.value.isEmpty()) {
                delay(10)
            }
        }

        val task = runner.tasks.value.first()
        assertTrue("Task should have keepScreenOn set to true", task.keepScreenOn)

        gate.complete(Unit)
    }
}
