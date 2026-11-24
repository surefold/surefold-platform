package dev.surefold.tenancy.crypto

import dev.surefold.capturingMetrics
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.resourceScope

/**
 * What one use of a held key view costs in committed steps (spec 0004). Every committed step swaps the one commit
 * count all steps share, so each is a point where callers of unrelated keys wait on one another.
 */
object KeyViewUseCostSuite : Suite() {
    // Lowered by each entry of spec 0004 as the use path sheds steps; never raised.
    private const val STEPS_ALLOWED = 3.0

    init {
        test("one use of a held key view commits no more steps than spec 0004 allows") {
            val cache = KeyCache()
            val key = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
            resourceScope {
                onRelease { _ -> cache.revokeAll() }
                val loaded = Either.Right(DataKey(key.owner, key.purpose, key.version, byteArrayOf(3)))
                val view = (cache.lease(key) { loaded } as Either.Right).value
                onRelease { _ -> view.close() }
                view.using { it.single() }

                capturingMetrics { measured ->
                    view.using { it.single().toInt() } shouldBe Either.Right(3)

                    val steps = measured.counter("surefold.transact.runs")
                    withClue("one use committed $steps steps") { (steps <= STEPS_ALLOWED) shouldBe true }
                }
            }
        }
    }
}
