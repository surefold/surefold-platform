package io.github.matthewjones372.surefold.jobs.tenancy

import io.github.matthewjones372.surefold.jobs.JobRunInfo
import io.github.matthewjones372.surefold.jobs.JobRunWatcher
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.withTenant

/**
 * Runs each job's handler as the tenant its row names (spec 0225), so a handler's `TenantTransactions.transaction`
 * sees that tenant's rows and no other's. The row's `tenant_id` decides, over any tenant the enqueuing thread carried:
 * it is what the job was enqueued for. A job with no tenant runs with none, and its handler's tenant transactions are
 * refused. Registered through `META-INF/services`, so having this module on the classpath is enough.
 */
class TenantOfJob : JobRunWatcher {
    override fun around(run: JobRunInfo, block: () -> String): String =
        run.tenant?.let { withTenant(TenantId(it), block) } ?: block()
}
