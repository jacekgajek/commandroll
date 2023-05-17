import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.throwable.shouldHaveMessage
import pl.jacekgajek.commandroll.ICommand
import pl.jacekgajek.commandroll.ICommandBindingBlock
import pl.jacekgajek.commandroll.command
import pl.jacekgajek.commandroll.commandBinding

class CommandTest : FunSpec() {

    private var okCalls = 0
    private var okRollbackCalls = 0
    private var okCalls2 = 0
    private var okRollbackCalls2 = 0
    private var failCalls = 0
    private var failRollbackCalls = 0
    private var okCmd: ICommand<String> = command("", { okCalls++; Result.success("result") }) { okRollbackCalls++; Result.success("rollback") }
    private var okCmd2: ICommand<String> = command("", { okCalls2++; Result.success("result") }) { okRollbackCalls2++; Result.success("rollback") }
    private var failingCmd: ICommand<String> =
        command("", { failCalls++; Result.failure(IllegalStateException("some error")) }) { okRollbackCalls++; Result.success("rollback") }

    init {
        beforeTest {
            okCmd = command("", { okCalls++; Result.success("result") }) { okRollbackCalls++; Result.success("rollback") }
            failingCmd = command("", { failCalls++; Result.failure(IllegalStateException("some error")) }) { okRollbackCalls++; Result.success("rollback") }
            okCalls = 0
            okRollbackCalls = 0
            okCalls2 = 0
            okRollbackCalls2 = 0
            failCalls = 0
            failRollbackCalls = 0
        }

        test("WHEN not all enqueued commands succeeds THEN err and rollback") {
            val result = commandBinding {
                okCmd.enqueueCommand()
                failingCmd.enqueueCommand()
                bindQueuedCommands()
            }
            result.isSuccess shouldBe false
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()!! shouldHaveMessage("some error")
            okCalls shouldBe 1
            failCalls shouldBe 1
            okRollbackCalls shouldBe 1
            failRollbackCalls shouldBe 0
        }

        test("WHEN all enqueued commands succeeds THEN result is returned") {
            val result = commandBinding {
                okCmd.enqueueCommand()
                bindQueuedCommands()
            }
            result.isSuccess shouldBe true
            result.getOrNull() shouldBe listOf("result")
            okCalls shouldBe 1
            failCalls shouldBe 0
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN a cmd is transferred to parent AND parent bind AND then command fail THEN command is rolled back") {
            commandBinding {
                commandBinding(this) {
                    okCmd.enqueueCommand()
                }.bind()
                okCalls shouldBe 0
                bindQueuedCommands()
                okCalls shouldBe 1
                commandBinding(this) { failingCmd.bindCommand() }.bind()
            }
            okCalls shouldBe 1
            failCalls shouldBe 1
            okRollbackCalls shouldBe 1
            failRollbackCalls shouldBe 0
        }

        test("WHEN a cmd is transferred to parent AND parent doesn't bind AND grandparent doesn't bind THEN exception") {
            shouldThrow<IllegalStateException> {
                commandBinding {
                    shouldNotThrow<IllegalStateException> {
                        commandBinding(this) {
                            commandBinding(this) {
                                okCmd.enqueueCommand()
                            }.bind()
                        }.bind()
                    }
                    okCalls shouldBe 0
                }
            }
            okCalls shouldBe 0
            failCalls shouldBe 0
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN a failing cmd is transferred to parent AND parent doesn't bind AND grandparent bind THEN command is rolled back") {
            commandBinding {
                commandBinding(this) {
                    commandBinding(this) {
                        okCmd.enqueueCommand()
                        failingCmd.enqueueCommand()
                    }.bind()
                }.bind()
                bindQueuedCommands()
            }
            okCalls shouldBe 1
            failCalls shouldBe 1
            okRollbackCalls shouldBe 1
            failRollbackCalls shouldBe 0
        }

        test("WHEN a cmd is transferred to parent AND parent doesn't bind AND grandparent bind THEN command is executed") {
            commandBinding {
                commandBinding(this) {
                    commandBinding(this) {
                        okCmd.enqueueCommand()
                    }.bind()
                }.bind()
                okCalls shouldBe 0
                bindQueuedCommands()
            }
            okCalls shouldBe 1
            failCalls shouldBe 0
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN a cmd is transferred to parent AND parent bind THEN command is executed") {
            commandBinding {
                commandBinding(this) {
                    okCmd.enqueueCommand()
                }.bind()
                okCalls shouldBe 0
                bindQueuedCommands()
            }
            okCalls shouldBe 1
            failCalls shouldBe 0
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN a cmd is transferred to parent AND parent doesn't bind THEN exception") {
            shouldThrow<IllegalStateException> {
                commandBinding {
                    commandBinding(this) {
                        okCmd.enqueueCommand()
                    }
                }
            }
            okCalls shouldBe 0
            failCalls shouldBe 0
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN a sibling binds queued commands THEN it doesn't affect outer queue") {
            commandBinding {
                commandBinding(this) {
                    commandBinding(this) {
                        okCmd.enqueueCommand()
                    }.bind()
                    okCalls shouldBe 0
                    okCalls2 shouldBe 0
                    commandBinding(this) {
                        okCmd2.enqueueCommand()
                        bindQueuedCommands()
                    }
                    okCalls shouldBe 0
                    okCalls2 shouldBe 1
                }.bind()
                bindQueuedCommands()
                okCalls shouldBe 1
                okCalls2 shouldBe 1
            }
            okCalls shouldBe 1
            okCalls2 shouldBe 1
            failCalls shouldBe 0
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN all commands succeeds THEN result is returned") {
            val result = commandBinding {
                okCmd.bindCommand()
            }
            result.isSuccess shouldBe true
            result.getOrNull() shouldBe "result"
            okCalls shouldBe 1
            failCalls shouldBe 0
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN first command fails THEN second is not executed") {
            val result = commandBinding {
                failingCmd.bindCommand()
                okCmd.bindCommand()
            }
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()!! shouldHaveMessage("some error")
            okCalls shouldBe 0
            failCalls shouldBe 1
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN command executed twice THEN exception") {
            shouldThrow<IllegalStateException> {
                commandBinding {
                    okCmd.bindCommand()
                    okCmd.bindCommand()
                }
            }
        }

        test("WHEN bindCommands not called THEN exception") {
            shouldThrow<IllegalStateException> {
                commandBinding {
                    okCmd.enqueueCommand()
                }
            }
        }

        test("WHEN a command in a sibling nested block fails THEN the first is NOT rolled back") {
            val result = commandBinding {
                commandBinding {
                    okCmd.bindCommand()
                }.bind()
                commandBinding {
                    failingCmd.bindCommand()
                }.bind()
            }
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()!! shouldHaveMessage("some error")
            okCalls shouldBe 1
            failCalls shouldBe 1
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN a command in a sibling nested block fails and parent is provided THEN the first is rolled back") {
            val result = commandBinding {
                commandBinding(this) {
                    okCmd.bindCommand()
                }.bind()
                commandBinding(this) {
                    failingCmd.bindCommand()
                }.bind()
            }
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()!! shouldHaveMessage("some error")
            okCalls shouldBe 1
            failCalls shouldBe 1
            okRollbackCalls shouldBe 1
            failRollbackCalls shouldBe 0
        }

        test("WHEN a nested block fails THEN the parent block terminates") {
            commandBinding {
                commandBinding(this) { failingCmd.bindCommand() }.bind()
                okCmd.bindCommand()
            }
            okCalls shouldBe 0
            failCalls shouldBe 1
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN second command fails THEN the first is rolled back") {
            val result = commandBinding {
                okCmd.bindCommand()
                failingCmd.bindCommand()
            }
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()!! shouldHaveMessage("some error")
            okCalls shouldBe 1
            failCalls shouldBe 1
            okRollbackCalls shouldBe 1
            failRollbackCalls shouldBe 0
        }

        test("WHEN command fails THEN queued commands don't execute") {
            val result = commandBinding {
                okCmd.enqueueCommand()
                failingCmd.bindCommand()
                bindQueuedCommands()
            }
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()!! shouldHaveMessage("some error")
            okCalls shouldBe 0
            failCalls shouldBe 1
            okRollbackCalls shouldBe 0
            failRollbackCalls shouldBe 0
        }

        test("WHEN a command is OK executed in a shared context and other fails THEN Err and the first is rolled back") {
            val result = commandBinding {
                sharedContextOp()
                failingCmd.bindCommand()
            }
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()!! shouldHaveMessage("some error")
            okCalls shouldBe 1
            failCalls shouldBe 1
            okRollbackCalls shouldBe 1
            failRollbackCalls shouldBe 0
        }
    }

    private fun ICommandBindingBlock.sharedContextOp() {
        okCmd.bindCommand()
    }
}
