package com.onesignal.core.internal.operations.impl

import com.onesignal.common.threading.WaiterWithValue
import com.onesignal.core.internal.config.ConfigModelStore
import com.onesignal.core.internal.operations.ExecutionResult
import com.onesignal.core.internal.operations.GroupComparisonType
import com.onesignal.core.internal.operations.IOperationExecutor
import com.onesignal.core.internal.operations.IOperationRepo
import com.onesignal.core.internal.operations.Operation
import com.onesignal.core.internal.startup.IStartableService
import com.onesignal.core.internal.time.ITime
import com.onesignal.debug.LogLevel
import com.onesignal.debug.internal.logging.Logging
import com.onesignal.user.internal.operations.impl.states.NewRecordsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.max
import kotlin.reflect.KClass

internal class OperationRepo(
    executors: List<IOperationExecutor>,
    private val _operationModelStore: OperationModelStore,
    private val _configModelStore: ConfigModelStore,
    private val _time: ITime,
    private val _newRecordState: NewRecordsState,
) : IOperationRepo, IStartableService {
    internal class OperationQueueItem(
        val operation: Operation,
        val waiter: WaiterWithValue<Boolean>? = null,
        val bucket: Int,
        var retries: Int = 0,
    ) {
        override fun toString(): String {
            return "bucket:$bucket, retries:$retries, operation:$operation\n"
        }
    }

    internal class LoopWaiterMessage(
        val force: Boolean,
        val previousWaitedTime: Long = 0,
    )

    private val executorsMap: Map<String, IOperationExecutor>
    private val queue = mutableListOf<OperationQueueItem>()
    private val waiter = WaiterWithValue<LoopWaiterMessage>()
    private var paused = false
    private var coroutineScope = CoroutineScope(newSingleThreadContext(name = "OpRepo"))

    /** *** Buckets ***
     * Purpose: Bucketing is a pattern we are using to help save network
     * calls. It works together with opRepoExecutionInterval to define
     * a time window operations can be added to the bucket.
     *
     * When enqueue() is called it creates a new OperationQueueItem with it's
     * bucket = enqueueIntoBucket. Just before we start processing a bucket we
     * enqueueIntoBucket++, this ensures anything new that comes in while
     * executing doesn't cause it to skip the opRepoExecutionInterval delay.
     *
     * NOTE: Bucketing only effects the starting operation we grab.
     *       The reason is we still want getGroupableOperations() to find
     *       other operations it can execute in one go (same network call).
     *       It's more efficient overall, as it lowers the total number of
     *       network calls.
     */
    private var enqueueIntoBucket = 0
    private val executeBucket get() =
        if (enqueueIntoBucket == 0) 0 else enqueueIntoBucket - 1

    init {
        val executorsMap: MutableMap<String, IOperationExecutor> = mutableMapOf()
        for (executor in executors) {
            for (operation in executor.operations) {
                executorsMap[operation] = executor
            }
        }
        this.executorsMap = executorsMap
    }

    override fun <T : Operation> containsInstanceOf(type: KClass<T>): Boolean {
        synchronized(queue) {
            return queue.any { type.isInstance(it.operation) }
        }
    }

    override fun start() {
        paused = false
        coroutineScope.launch {
            // load saved operations first then start processing the queue to ensure correct operation order
            loadSavedOperations()
            processQueueForever()
        }
    }

    override fun enqueue(
        operation: Operation,
        flush: Boolean,
    ) {
        Logging.log(LogLevel.DEBUG, "OperationRepo.enqueue(operation: $operation, flush: $flush)")

        operation.id = UUID.randomUUID().toString()
        internalEnqueue(OperationQueueItem(operation, bucket = enqueueIntoBucket), flush, true)
    }

    override suspend fun enqueueAndWait(
        operation: Operation,
        flush: Boolean,
    ): Boolean {
        Logging.log(LogLevel.DEBUG, "OperationRepo.enqueueAndWait(operation: $operation, force: $flush)")

        operation.id = UUID.randomUUID().toString()
        val waiter = WaiterWithValue<Boolean>()
        internalEnqueue(OperationQueueItem(operation, waiter, bucket = enqueueIntoBucket), flush, true)
        return waiter.waitForWake()
    }

    // WARNING: Never set to true, until budget rules are added, even for internal use!
    private fun internalEnqueue(
        queueItem: OperationQueueItem,
        flush: Boolean,
        addToStore: Boolean,
        index: Int? = null,
    ) {
        synchronized(queue) {
            if (index != null) {
                queue.add(index, queueItem)
            } else {
                queue.add(queueItem)
            }
        }
        if (addToStore) {
            _operationModelStore.add(queueItem.operation)
        }

        waiter.wake(LoopWaiterMessage(flush, 0))
    }

    /**
     * The background processing that will never return.  This should be called on it's own
     * dedicated thread.
     */
    private suspend fun processQueueForever() {
        waitForNewOperationAndExecutionInterval()
        enqueueIntoBucket++
        while (true) {
            if (paused) {
                Logging.debug("OperationRepo is paused")
                return
            }

            val ops = getNextOps(executeBucket)
            Logging.debug("processQueueForever:ops:\n$ops")

            if (ops != null) {
                executeOperations(ops)
                // Allows for any subsequent operations (beyond the first one
                // that woke us) to be enqueued before we pull from the queue.
                delay(_configModelStore.model.opRepoPostWakeDelay)
            } else {
                waitForNewOperationAndExecutionInterval()
                enqueueIntoBucket++
            }
        }
    }

    /**
     *  Waits until a new operation is enqueued, then wait an additional
     *  amount of time afterwards, so operations can be grouped/batched.
     *  NOTE: Any operations that are enqueued while waiting here causes
     *        the wait timer to restart over. This is intentional, we
     *        are basically wait for "the dust to settle" / "the water
     *        is calm" to ensure the app is done making updates.
     *  FUTURE: Highly recommend not removing this "the dust to settle"
     *          logic, as it ensures any app stuck in a loop can't
     *          cause continuous network requests. If the delay is too
     *          long for legitimate use-cases then allow tweaking the
     *          opRepoExecutionInterval value or allow commitNow()
     *          with a budget.
     */
    private suspend fun waitForNewOperationAndExecutionInterval() {
        // 1. Wait for an operation to be enqueued
        var wakeMessage = waiter.waitForWake()

        // 2. Now wait opRepoExecutionInterval, restart the wait
        //    time everytime something new is enqueued, to ensure
        //    the dust has settled.
        var remainingTime = _configModelStore.model.opRepoExecutionInterval - wakeMessage.previousWaitedTime
        while (!wakeMessage.force) {
            val waitedTheFullTime =
                withTimeoutOrNull(remainingTime) {
                    wakeMessage = waiter.waitForWake()
                } == null
            if (waitedTheFullTime) break
            remainingTime = _configModelStore.model.opRepoExecutionInterval
        }
    }

    internal suspend fun executeOperations(ops: List<OperationQueueItem>) {
        try {
            val startingOp = ops.first()
            val executor =
                executorsMap[startingOp.operation.name]
                    ?: throw Exception("Could not find executor for operation ${startingOp.operation.name}")

            val operations = ops.map { it.operation }
            val response = executor.execute(operations)

            Logging.debug("OperationRepo: execute response = ${response.result}")

            // if the execution resulted in ID translations, run through the queue so they pick it up.
            // We also run through the ops just executed in case they are re-added to the queue.
            if (response.idTranslations != null) {
                ops.forEach { it.operation.translateIds(response.idTranslations) }
                synchronized(queue) {
                    queue.forEach { it.operation.translateIds(response.idTranslations) }
                }
                response.idTranslations.values.forEach { _newRecordState.add(it) }
                coroutineScope.launch {
                    val waitTime = _configModelStore.model.opRepoPostCreateDelay
                    delay(waitTime)
                    synchronized(queue) {
                        if (queue.isNotEmpty()) waiter.wake(LoopWaiterMessage(false, waitTime))
                    }
                }
            }

            var highestRetries = 0
            when (response.result) {
                ExecutionResult.SUCCESS -> {
                    // on success we remove the operation from the store and wake any waiters
                    ops.forEach { _operationModelStore.remove(it.operation.id) }
                    ops.forEach { it.waiter?.wake(true) }
                }
                ExecutionResult.FAIL_UNAUTHORIZED, // TODO: Need to provide callback for app to reset JWT. For now, fail with no retry.
                ExecutionResult.FAIL_NORETRY,
                ExecutionResult.FAIL_CONFLICT,
                -> {
                    Logging.error("Operation execution failed without retry: $operations")
                    // on failure we remove the operation from the store and wake any waiters
                    ops.forEach { _operationModelStore.remove(it.operation.id) }
                    ops.forEach { it.waiter?.wake(false) }
                }
                ExecutionResult.SUCCESS_STARTING_ONLY -> {
                    // remove the starting operation from the store and wake any waiters, then
                    // add back all but the starting op to the front of the queue to be re-executed
                    _operationModelStore.remove(startingOp.operation.id)
                    startingOp.waiter?.wake(true)
                    synchronized(queue) {
                        ops.filter { it != startingOp }.reversed().forEach { queue.add(0, it) }
                    }
                }
                ExecutionResult.FAIL_RETRY -> {
                    Logging.error("Operation execution failed, retrying: $operations")
                    // add back all operations to the front of the queue to be re-executed.
                    synchronized(queue) {
                        ops.reversed().forEach {
                            if (++it.retries > highestRetries) {
                                highestRetries = it.retries
                            }
                            queue.add(0, it)
                        }
                    }
                }
                ExecutionResult.FAIL_PAUSE_OPREPO -> {
                    Logging.error("Operation execution failed with eventual retry, pausing the operation repo: $operations")
                    // keep the failed operation and pause the operation repo from executing
                    paused = true
                    // add back all operations to the front of the queue to be re-executed.
                    synchronized(queue) {
                        ops.reversed().forEach { queue.add(0, it) }
                    }
                }
            }

            // if there are operations provided on the result, we need to enqueue them at the
            // beginning of the queue.
            if (response.operations != null) {
                synchronized(queue) {
                    for (op in response.operations.reversed()) {
                        op.id = UUID.randomUUID().toString()
                        val queueItem = OperationQueueItem(op, bucket = 0)
                        queue.add(0, queueItem)
                        _operationModelStore.add(0, queueItem.operation)
                    }
                }
            }

            delayBeforeNextExecution(highestRetries, response.retryAfterSeconds)
        } catch (e: Throwable) {
            Logging.log(LogLevel.ERROR, "Error attempting to execute operation: $ops", e)

            // on failure we remove the operation from the store and wake any waiters
            ops.forEach { _operationModelStore.remove(it.operation.id) }
            ops.forEach { it.waiter?.wake(false) }
        }
    }

    /**
     * Wait which ever is longer, retryAfterSeconds returned by the server,
     * or based on the retry count.
     */
    suspend fun delayBeforeNextExecution(
        retries: Int,
        retryAfterSeconds: Int?,
    ) {
        Logging.debug("retryAfterSeconds: $retryAfterSeconds")
        val retryAfterSecondsNonNull = retryAfterSeconds?.toLong() ?: 0L
        val delayForOnRetries = retries * _configModelStore.model.opRepoDefaultFailRetryBackoff
        val delayFor = max(delayForOnRetries, retryAfterSecondsNonNull * 1_000)
        if (delayFor < 1) return
        Logging.error("Operations being delay for: $delayFor ms")
        delay(delayFor)
    }

    internal fun getNextOps(bucketFilter: Int): List<OperationQueueItem>? {
        return synchronized(queue) {
            val startingOp =
                queue.firstOrNull {
                    it.operation.canStartExecute &&
                        _newRecordState.canAccess(it.operation.applyToRecordId) &&
                        it.bucket <= bucketFilter
                }

            if (startingOp != null) {
                queue.remove(startingOp)
                getGroupableOperations(startingOp)
            } else {
                null
            }
        }
    }

    /**
     * Given a starting operation, find and remove from the queue all other operations that
     * can be executed along with the starting operation.  The full list of operations, with
     * the starting operation being first, will be returned.
     *
     * THIS SHOULD BE CALLED WHILE THE QUEUE IS SYNCHRONIZED!!
     */
    private fun getGroupableOperations(startingOp: OperationQueueItem): List<OperationQueueItem> {
        val ops = mutableListOf<OperationQueueItem>()
        ops.add(startingOp)

        if (startingOp.operation.groupComparisonType == GroupComparisonType.NONE) {
            return ops
        }

        val startingKey =
            if (startingOp.operation.groupComparisonType == GroupComparisonType.CREATE) {
                startingOp.operation.createComparisonKey
            } else {
                startingOp.operation.modifyComparisonKey
            }

        if (queue.isNotEmpty()) {
            for (item in queue.toList()) {
                val itemKey =
                    if (startingOp.operation.groupComparisonType == GroupComparisonType.CREATE) {
                        item.operation.createComparisonKey
                    } else {
                        item.operation.modifyComparisonKey
                    }

                if (itemKey == "" && startingKey == "") {
                    throw Exception("Both comparison keys can not be blank!")
                }

                if (itemKey == startingKey) {
                    queue.remove(item)
                    ops.add(item)
                }
            }
        }

        return ops
    }

    /**
     * Load saved operations from preference service and add them into the queue
     * NOTE: Sometimes the loading might take longer than expected due to I/O reads from disk
     *      Any I/O implies executing time will vary greatly.
     */
    private fun loadSavedOperations() {
        _operationModelStore.loadOperations()
        for (operation in _operationModelStore.list().withIndex()) {
            internalEnqueue(
                OperationQueueItem(operation.value, bucket = enqueueIntoBucket),
                flush = false,
                addToStore = false,
                operation.index,
            )
        }
    }
}
