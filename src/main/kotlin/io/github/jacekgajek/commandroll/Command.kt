package io.github.jacekgajek.commandroll

import mu.KotlinLogging
import java.util.Deque
import java.util.LinkedList
import java.util.Queue

private val log = KotlinLogging.logger {}

fun <T : Any> command(name: String = "", command: () -> Result<T>): ICommand<T> = Command(name, command)
fun <T : Any> command(name: String = "", command: () -> Result<T>, rollbackFun: (T) -> Result<*>): ICommand<T> =
    Command(name, command, rollbackFun)

/**
 * Calls the specified function [block] with [ICommandBindingBlock] as its receiver and returns its [Result].
 *
 * When inside a [commandBinding] block, the [bindCommand][ICommandBindingBlock.bindCommand] and [enqueueCommand][ICommandBindingBlock.enqueueCommand]
 * are available on any [ICommand].
 *
 * Calling the [bindCommand][ICommandBindingBlock.bindCommand] function will attempt to execute a command and locally return its return value. If
 * the [Result] is a failure, a [rollback][ICommand.rollback] will be called on all so far executed commands and the binding block will
 * terminate and return that failed-to-bind command.
 *
 * The [bind][ICommandBindingBlock.bind] function which is available on [Result] tries to unwrap a value from a [Result].
 * If it fails, the commandBinding block is terminated and returns that failed-to-bind Result
 *
 * The [enqueueCommand][ICommandBindingBlock.enqueueCommand] method puts a command to a queue to be executed later.
 * The [bindQueuedCommands][ICommandBindingBlock.bindQueuedCommands] method binds all commands in the queue.
 *
 * @see ICommandBindingBlock
 */
fun <T : Any> commandBinding(rollbackStrategy: RollbackStrategy = RollbackStrategy.OnErrorTerminate, block: ICommandBindingBlock.() -> T): Result<T> {
    return commandBinding(null, rollbackStrategy, block)
}

/**
 * The same as [commandBinding], but additionally adds executed commands to a collection stored in another commandBinding block.
 * Additionally, all queued commands which were not bound will bubble up to the parent block.
 * Use if your transaction spans multiple classes or public functions. For private funs you can use
 * the syntax of extension functions:
 * ```
 * fun ICommandBindingBlock.funName(): T { ... }
 * ```
 */
fun <T : Any> commandBinding(
    parent: ICommandBindingBlock?,
    rollbackStrategy: RollbackStrategy = RollbackStrategy.OnErrorTerminate,
    block: ICommandBindingBlock.() -> T
): Result<T> {
    val queues = (parent as? CommandBindingBlock)?.queueStack ?: LinkedList()

    val blockImpl = CommandBindingBlock(queueStack = queues, rollbackStrategy = rollbackStrategy)
    return try {
        val blockRes = block(blockImpl)
        val queue = queues.pop()
        if (parent is CommandBindingBlock) {
            parent.appendExecutedCommands(blockImpl)
            parent.enqueueCommands(queue)
        } else {
            ensureQueueIsEmpty(queue)
        }
        Result.success(blockRes)
    } catch (e: CommandBindingBlockException) {
        Result.failure(e.reason)
    }
}

private fun ensureQueueIsEmpty(unexecutedCommands: Collection<ICommand<*>>) {
    unexecutedCommands
        .takeIf { it.isNotEmpty() }
        ?.map { "'$it'" }
        ?.let { names ->
            throw IllegalStateException(
                "These commands were queued in a commandBinding block but not executed: $names. Call the bindQueuedCommands() method or share the commandBinding context."
            )
        }
}

enum class RollbackStrategy {
    /**
     * If a rollback throws an exception, the rollback process is terminated and the exception is rethrown
     */
    OnErrorTerminate,

    /**
     * If a rollback throws an exception, the rollback process is continued and the first occurred exception is rethrown in the
     * end, with all suppressed exception accessible with [Throwable.suppressedExceptions].
     */
    OnErrorContinue,
}

interface ICommand<T : Any> {
    /**
     * Descriptive name of the command
     */
    val name: String

    /**
     * Execute the command. If this method throws an exception, it is wrapped in Result.failure(e).
     */
    fun execute(): Result<T>

    /**
     * Undo actions performed by the [execute] method.
     */
    fun rollback(): Result<*>?
}

interface ICommandBindingBlock {
    /**
     * Execute a command. If it fails, rollback all previous commands.
     */
    fun <T : Any> ICommand<T>.bindCommand(): T

    /**
     * Bind a Result. If it fails, rollback all executed commands.
     */
    fun <V> Result<V>.bind(): V

    /**
     * Add a command to a queue. Commands in a queue need to be explicitly bound within the same commandBinding block
     * or a parent commandBinding (or higher).
     *
     * @see bindQueuedCommands
     */
    fun <T : Any> ICommand<T>.enqueueCommand()

    /**
     * Bind all enqueued commands, clear the queue and return an array of their results.
     * If any command fails then all executed commands in the bindingBlock are rolled back,
     * including executed commands in the queue, if any.
     */
    fun bindQueuedCommands(): List<Any>
}

private class Command<T : Any>(
    override val name: String,
    private val executor: () -> Result<T>,
    private val rollbackFun: ((T) -> Result<*>)? = null
) : ICommand<T> {
    private var result: T? = null

    override fun execute(): Result<T> {
        if (result != null) {
            throw IllegalStateException("This command ('$name') has already been executed.")
        }
        log.debug { "Executing command: '$name'" }
        val result = runCatching { executor() }.flatten()
        this.result = result.getOrNull()
        return result
    }

    override fun rollback(): Result<*>? {
        val result = this.result ?: throw IllegalStateException("Cannot rollback a command which hasn't been successfully executed ('$name').")
        val rollbackFun = this.rollbackFun
        return if (rollbackFun != null) {
            log.info { "Trying to rollback command '$name'..." }
            rollbackFun(result)
                .also {
                    it.fold(
                        { log.info { "Successfully rolled-back command '$name' which previous result was $result..." } }
                    ) {
                        log.error("Rollback of command '$name' has failed which previous result was $result...", it)
                    }
                }
        } else {
            log.error { "Cannot rollback a command: No rollback action defined for command '$name' which previous result was $result." }
            null
        }
    }

    override fun toString(): String = name
}

private class CommandBindingBlockException(val reason: Throwable) : RuntimeException()

private class CommandBindingBlock(
    private val executedCommands: MutableList<ICommand<*>> = LinkedList(),
    val queueStack: Deque<Queue<ICommand<*>>>,
    private val rollbackStrategy: RollbackStrategy
) : ICommandBindingBlock {
    init {
        queueStack.push(LinkedList())
    }

    override fun <T : Any> ICommand<T>.bindCommand(): T {
        val result = this.execute()
        result.onFailure {
            logBindFail("Command '${this.name}'", it)
            rollbackExecutedCommands()
            throw CommandBindingBlockException(it)
        }
        executedCommands.add(this)
        return result.getOrThrow()
    }

    override fun <T : Any> ICommand<T>.enqueueCommand() {
        localQueue().offer(this)
    }

    override fun bindQueuedCommands(): List<Any> {
        val results = mutableListOf<Any>()
        val queue = localQueue()
        if (queue.isEmpty()) {
            log.warn("The bindQueuedCommands() was called but no commands are queued!", Exception())
        }
        while (queue.isNotEmpty()) {
            val c = queue.poll()
            results.add(c.bindCommand())
        }
        return results
    }

    override fun <V> Result<V>.bind(): V {
        return this.getOrElse {
            logBindFail("Bind expression", it)
            rollbackExecutedCommands()
            throw CommandBindingBlockException(it)
        }
    }

    private fun logBindFail(what: String, how: Throwable) {
        if (executedCommands.isEmpty())
            log.info("$what failed with exception $how. No commands to rollback though.", how)
        else
            log.info("$what failed with error $how. Rollback started...", how)
    }

    fun enqueueCommands(command: Iterable<ICommand<*>>) = command.forEach { localQueue().offer(it) }

    fun appendExecutedCommands(block: ICommandBindingBlock) {
        if (block !is CommandBindingBlock) {
            throw IllegalArgumentException("Cannot append commands from $block")
        }
        executedCommands.addAll(block.executedCommands)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rollbackExecutedCommands() {
        val rolledBack = mutableListOf<ICommand<*>>()
        val suppressedExceptions = mutableListOf<Throwable>()
        if (localQueue().isNotEmpty()) {
            log.warn { "These commands where queued but won't be executed because a rollback was triggered: ${localQueue()}." }
        }
        while (executedCommands.isNotEmpty()) {
            val c = executedCommands.removeLast()
            try {
                c.rollback()
                rolledBack.add(c)
            } catch (e: Throwable) {
                when (rollbackStrategy) {
                    RollbackStrategy.OnErrorTerminate -> {
                        log.error {
                            "Rollback terminated because of an unhandled exception. Commands which weren't rolled back: $executedCommands." +
                                    " Rolled back: $rolledBack. Exception: '${e.message}'. Stack trace: ${e.stackTraceToString()}"
                        }
                        throw e
                    }

                    RollbackStrategy.OnErrorContinue -> {
                        log.error {
                            "Failed to rollback command: '${c.name}'. Exception: '${e.message}'. Stack trace: ${e.stackTraceToString()}. Trying to rollback the remaining commands " +
                                    "because the rollbackStrategy was set to $rollbackStrategy"
                        }
                        suppressedExceptions.add(e)
                    }
                }
            }
        }
        if (suppressedExceptions.isNotEmpty()) {
            log.error { "Roll back failed for one or more commands. Throwing the last exception. The suppressed exception are available under Throwable.getSuppressed()" }
            val e = suppressedExceptions.first()
            suppressedExceptions.drop(1).forEach { e.addSuppressed(it) }
            throw e
        }
    }

    private fun localQueue() = queueStack.peek() ?: throw IllegalStateException("Queue not initialized")
}

inline fun <T, K> Result<T>.flatMap(mapper: (T) -> Result<K>): Result<K> =
    if (isFailure)
        Result.failure(exceptionOrNull()!!)
    else
        mapper(getOrThrow())

inline fun <T> Result<Result<T>>.flatten(): Result<T> =
    if (isFailure)
        Result.failure(this.exceptionOrNull()!!)
    else
        getOrThrow()
