/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmMultifileClass
@file:JvmName("BuildersKt")

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.*
import java.util.*
import java.util.concurrent.locks.*
import kotlin.coroutines.experimental.*

/**
 * Runs new coroutine and **blocks** current thread _interruptibly_ until its completion.
 * This function should not be used from coroutine. It is designed to bridge regular blocking code
 * to libraries that are written in suspending style, to be used in `main` functions and in tests.
 *
 * The default [CoroutineDispatcher] for this builder in an implementation of [EventLoop] that processes continuations
 * in this blocked thread until the completion of this coroutine.
 * See [CoroutineDispatcher] for the other implementations that are provided by `kotlinx.coroutines`.
 *
 * When [CoroutineDispatcher] is explicitly specified in the [context], then the new coroutine runs in the context of
 * the specified dispatcher while the current thread is blocked. If the specified dispatcher implements [EventLoop]
 * interface and this `runBlocking` invocation is performed from inside of the this event loop's thread, then
 * this event loop is processed using its [processNextEvent][EventLoop.processNextEvent] method until coroutine completes.
 *
 * If this blocked thread is interrupted (see [Thread.interrupt]), then the coroutine job is cancelled and
 * this `runBlocking` invocation throws [InterruptedException].
 *
 * See [newCoroutineContext] for a description of debugging facilities that are available for newly created coroutine.
 *
 * @throws IllegalStateException if blocking is not allowed in current thread.
 *         Blocking is checked by [BlockingChecker] that is registered via [ServiceLoader],
 *         unless [BLOCKING_CHECKER_PROPERTY_NAME] system property is set to [BLOCKING_CHECKER_VALUE_DISABLE].
 * @param context context of the coroutine. The default value is an implementation of [EventLoop].
 * @param block the coroutine code.
 */
@Throws(InterruptedException::class)
public fun <T> runBlocking(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> T): T {
    blockingChecker?.checkRunBlocking()
    val currentThread = Thread.currentThread()
    val contextInterceptor = context[ContinuationInterceptor]
    val privateEventLoop = contextInterceptor == null // create private event loop if no dispatcher is specified
    val eventLoop = if (privateEventLoop) BlockingEventLoop(currentThread) else contextInterceptor as? EventLoop
    val newContext = newCoroutineContext(
        if (privateEventLoop) context + (eventLoop as ContinuationInterceptor) else context
    )
    val coroutine = BlockingCoroutine<T>(newContext, currentThread, eventLoop, privateEventLoop)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
    return coroutine.joinBlocking()
}

/**
 * Name of the property to control whether [runBlocking] builder
 * is restricted via [BlockingChecker] extension points.
 */
public const val BLOCKING_CHECKER_PROPERTY_NAME = "kotlinx.coroutines.blocking.checker"

/**
 * Value of the [BLOCKING_CHECKER_PROPERTY_NAME] to disable installed
 * [BlockingChecker] and thus allow [runBlocking] in any thread.
 */
public const val BLOCKING_CHECKER_VALUE_DISABLE = "disable"

/**
 * Extension point which determines whether invoking [runBlocking] in the current thread is allowed.
 * [runBlocking] discovers all checkers via [ServiceLoader] and invokes [checkRunBlocking] on
 * each attempt to invoke [runBlocking].
 *
 * Common example is checking whether we're not in UI thread:
 *
 * ```
 * class UiChecker : BlockingChecker {
 *    fun checkRunBlocking() =
 *        check(!UiFramework.isInUiThread()) { "runBlocking is not allowed in UI thread" }
 * }
 * ```
 *
 * Installed checkers are ignored if "`kotlinx.coroutines.blocking.checker`" ([BLOCKING_CHECKER_PROPERTY_NAME])
 * system property is set to the value "`disable`" ([BLOCKING_CHECKER_VALUE_DISABLE]).
 */
public interface BlockingChecker {
    /**
     * Throws [IllegalStateException] if [runBlocking] calls are not allowed in the current thread.
     */
    fun checkRunBlocking()
}

private class BlockingCheckerList(private val checkers: Array<BlockingChecker>) : BlockingChecker {
    override fun checkRunBlocking() = checkers.forEach { it.checkRunBlocking() }
}

// Nullable to enable DCE when no filters are present in classpath
private val blockingChecker: BlockingChecker? = run {
    val value = systemProp(BLOCKING_CHECKER_PROPERTY_NAME)
    when (value) {
        BLOCKING_CHECKER_VALUE_DISABLE -> null
        null -> {
            val checkers = ServiceLoader.load(BlockingChecker::class.java).toList()
            when (checkers.size) {
                0 -> null
                1 -> checkers[0]
                else -> BlockingCheckerList(checkers.toTypedArray())
            }
        }
        else -> error("System property '$BLOCKING_CHECKER_PROPERTY_NAME' has unrecognized value '$value'")
    }
}

private class BlockingCoroutine<T>(
    parentContext: CoroutineContext,
    private val blockedThread: Thread,
    private val eventLoop: EventLoop?,
    private val privateEventLoop: Boolean
) : AbstractCoroutine<T>(parentContext, true) {
    init {
        if (privateEventLoop) require(eventLoop is BlockingEventLoop)
    }

    override fun onCancellationInternal(exceptionally: CompletedExceptionally?) {
        // wake up blocked thread
        if (Thread.currentThread() != blockedThread)
            LockSupport.unpark(blockedThread)
    }

    @Suppress("UNCHECKED_CAST")
    fun joinBlocking(): T {
        timeSource.registerTimeLoopThread()
        while (true) {
            if (Thread.interrupted()) throw InterruptedException().also { cancel(it) }
            val parkNanos = eventLoop?.processNextEvent() ?: Long.MAX_VALUE
            // note: process next even may loose unpark flag, so check if completed before parking
            if (isCompleted) break
            timeSource.parkNanos(this, parkNanos)
        }
        // process queued events (that could have been added after last processNextEvent and before cancel
        if (privateEventLoop) (eventLoop as BlockingEventLoop).apply {
            // We exit the "while" loop above when this coroutine's state "isCompleted",
            // Here we should signal that BlockingEventLoop should not accept any more tasks
            isCompleted = true
            shutdown()
        }
        timeSource.unregisterTimeLoopThread()
        // now return result
        val state = this.state
        (state as? CompletedExceptionally)?.let { throw it.cause }
        return state as T
    }
}
