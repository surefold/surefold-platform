package dev.surefold.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.withClue
import dev.surefold.test.eventually
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The polling worker: concurrency per kind, and a graceful close (spec 0216). */
object JobWorkerSuite : Suite() {
    private val quick = JobSettings(poll = 20.milliseconds, drain = 10.seconds)

    init {
        test("a worker runs every due job, never more of a kind at once than its concurrency") {
            val source = jobsDatabase()
            val three = object : JobKind<String>("three", Notes, concurrency = 3) {}
            val now = AtomicInteger()
            val most = AtomicInteger()
            val handlers = JobHandlers.of(
                three.handledBy {
                    most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                    Thread.sleep(30)
                    now.decrementAndGet()
                    JobResult.Done
                },
            )
            source.committed { connection -> (1..20).forEach { Jobs.enqueue(connection, three, "$it", "$it") } }
            JobWorker(source, handlers, quick).start().use {
                eventually(within = 30.seconds) {
                    source.column("select count(*) from surefold_jobs_history where status = 'done'") shouldBe
                        listOf(20L)
                }
            }
            most.get() shouldBe 3
        }

        test("a kind that fills its room claims again as a run ends, without waiting for the next poll") {
            val source = jobsDatabase()
            val one = object : JobKind<String>("one", Notes, concurrency = 1) {}
            val handlers = JobHandlers.of(one.handledBy { JobResult.Done })
            source.committed { connection -> (1..20).forEach { Jobs.enqueue(connection, one, "$it", "$it") } }
            // One poll at start and none after: only claims made as runs end can drain the other nineteen.
            JobWorker(source, handlers, JobSettings(poll = 10.minutes, listen = false)).start().use {
                eventually(within = 30.seconds) {
                    source.column("select count(*) from surefold_jobs_history where status = 'done'") shouldBe
                        listOf(20L)
                }
            }
        }

        test("claims made as runs end never hold off another kind past a poll") {
            val source = jobsDatabase()
            val busy = object : JobKind<String>("busy", Notes, concurrency = 1) {}
            val other = object : JobKind<String>("other", Notes, concurrency = 1) {}
            val handlers = JobHandlers.of(
                busy.handledBy {
                    Thread.sleep(5)
                    JobResult.Done
                },
                other.handledBy { JobResult.Done },
            )
            source.committed { connection -> (1..2000).forEach { Jobs.enqueue(connection, busy, "$it", "$it") } }
            JobWorker(source, handlers, JobSettings(poll = 200.milliseconds, listen = false)).start().use {
                // Due after the first poll, so only a later full round can claim it while busy keeps refilling.
                source.committed { connection ->
                    Jobs.enqueue(connection, other, "late", "late", runAt = java.time.Instant.now().plusMillis(300))
                }
                eventually(within = 3.seconds) {
                    source.column("select status from surefold_jobs_history where kind = 'other'") shouldBe
                        listOf("done")
                }
            }
        }

        test("a worker sleeps until the next job is due, however long its poll") {
            val source = jobsDatabase()
            val ran = AtomicBoolean(false)
            val handlers = JobHandlers.of(
                Remind.handledBy {
                    ran.set(true)
                    JobResult.Done
                },
            )
            source.committed { Jobs.enqueue(it, Remind, "soon", "soon", runAt = Instant.now().plusMillis(300)) }
            val settings = JobSettings(poll = 10.minutes, minPoll = 50.milliseconds, listen = false)
            JobWorker(source, handlers, settings).start().use {
                eventually(within = 2.seconds) { ran.get() shouldBe true }
            }
        }

        test("an idle worker claims once a poll, not once a minPoll") {
            val statements = ConcurrentLinkedQueue<String>()
            val source = jobsDatabase().noting(statements)
            val claims = { statements.count { "set status = 'running'" in it } }
            val settings = JobSettings(poll = 300.milliseconds, minPoll = 20.milliseconds, listen = false)
            JobWorker(source, JobHandlers.of(Remind.handledBy { JobResult.Done }), settings).start().use {
                eventually(within = 10.seconds) { (claims() >= 2) shouldBe true }
                val before = claims()
                Thread.sleep(1_000)
                // Two claims a round (remind, and the unhandled kinds), at most one round a poll: 1 s holds four.
                withClue("claims in 1 s") { claims() - before shouldBeLessThan 9 }
            }
        }

        test("a due job a rate holds back costs a round a minPoll, never a busy loop") {
            val statements = ConcurrentLinkedQueue<String>()
            val source = jobsDatabase().noting(statements)
            val limited = object : JobKind<String>("limited", Notes, rate = JobRate(1, per = 10.minutes)) {}
            source.committed { connection -> (1..2).forEach { Jobs.enqueue(connection, limited, "$it", "$it") } }
            val settings = JobSettings(poll = 10.minutes, minPoll = 50.milliseconds, listen = false)
            JobWorker(source, JobHandlers.of(limited.handledBy { JobResult.Done }), settings).start().use {
                eventually(within = 10.seconds) {
                    source.column("select count(*) from surefold_jobs_history") shouldBe listOf(1L)
                }
                val before = statements.size
                Thread.sleep(1_000)
                // A round is at most five statements: the bucket's three, the unhandled claim, the next run time.
                withClue("statements in 1 s") { statements.size - before shouldBeLessThan 5 * (1_000 / 50 + 2) }
            }
        }

        test("a worker fails a job of a kind it has no handler for") {
            val source = jobsDatabase()
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
            JobWorker(source, JobHandlers.of(), quick).start().use {
                eventually(within = 30.seconds) {
                    source.column("select status from surefold_jobs_history") shouldBe listOf("failed")
                }
            }
        }

        test("closing lets a run in flight finish within the drain, and claims nothing after") {
            val source = jobsDatabase()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val handlers = JobHandlers.of(
                Remind.handledBy {
                    started.countDown()
                    release.await()
                    JobResult.Done
                },
            )
            source.committed { connection ->
                Jobs.enqueue(connection, Remind, "first", "first")
                Jobs.enqueue(connection, Remind, "second", "second", runAt = java.time.Instant.now().plusSeconds(1))
            }
            val worker = JobWorker(source, handlers, quick).start()
            started.await(30, TimeUnit.SECONDS) shouldBe true
            val closing = Thread.ofVirtual().start { worker.close() }
            Thread.sleep(1_500)
            release.countDown()
            closing.join()
            source.column("select dedupe_key || ' ' || status from surefold_jobs_history") shouldBe listOf("first done")
            source.column("select dedupe_key || ' ' || status || ' ' || attempts from surefold_jobs") shouldBe
                listOf("second pending 0")
        }

        test("closing interrupts a run still going when the drain ends, and leaves its lease to expire") {
            val source = jobsDatabase()
            val started = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            val handlers = JobHandlers.of(
                Remind.handledBy {
                    started.countDown()
                    try {
                        Thread.sleep(60_000)
                    } catch (_: InterruptedException) {
                        interrupted.set(true)
                    }
                    JobResult.Done
                },
            )
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
            val worker = JobWorker(source, handlers, quick.copy(drain = 200.milliseconds)).start()
            started.await(30, TimeUnit.SECONDS) shouldBe true
            val closing = TimeSource.Monotonic.markNow()
            worker.close()
            closing.elapsedNow() shouldBeLessThan 5.seconds
            eventually(within = 5.seconds) { interrupted.get() shouldBe true }
        }
    }
}

/** This source, noting in [sql] what every statement prepared on it runs. */
private fun DataSource.noting(sql: MutableCollection<String>): DataSource {
    val loader = JobWorkerSuite::class.java.classLoader
    val source = this
    return Proxy.newProxyInstance(loader, arrayOf(DataSource::class.java)) { _, method, args ->
        val made = method.invoke(source, *args.orEmpty())
        if (made !is Connection) return@newProxyInstance made
        Proxy.newProxyInstance(loader, arrayOf(Connection::class.java)) { _, call, given ->
            if (call.name == "prepareStatement") sql += given[0] as String
            call.invoke(made, *given.orEmpty())
        }
    } as DataSource
}
