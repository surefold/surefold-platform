package io.github.matthewjones372.surefold.tenancy

import io.github.matthewjones372.surefold.Carrier
import io.github.matthewjones372.surefold.SurefoldLocal
import io.github.matthewjones372.surefold.surefoldLocal

/** Whose data a piece of work is about: a tenant's id, as the service names it. */
@JvmInline
value class TenantId(val value: String) {
    init {
        require(value.isNotBlank()) { "A tenant id cannot be blank." }
    }

    override fun toString(): String = value
}

/** The tenant the current work is for, or null. A fork inherits it, as it inherits the clock and the logger. */
val tenant: SurefoldLocal<TenantId?> = surefoldLocal { null }

/** Runs [block], and every fork it opens, as [id]'s. */
fun <A> withTenant(id: TenantId, block: () -> A): A = tenant.locally(id, block)

/** The tenant the current work is for, or null outside any [withTenant]. */
fun currentTenant(): TenantId? = tenant.get()

/**
 * Carries the tenant across a `tell` to an actor and into an enqueued job, beside the trace (spec 0225). Registered
 * through `META-INF/services`, so having this module on the classpath is enough.
 */
class TenantCarrier : Carrier {
    override fun capture(): Map<String, String> = tenant.get()?.let { mapOf(KEY to it.value) }.orEmpty()

    override fun <A> within(carried: Map<String, String>, block: () -> A): A =
        carried[KEY]?.let { withTenant(TenantId(it), block) } ?: block()

    private companion object {
        const val KEY = "surefold.tenant"
    }
}
