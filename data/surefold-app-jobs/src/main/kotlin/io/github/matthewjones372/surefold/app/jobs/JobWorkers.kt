package io.github.matthewjones372.surefold.app.jobs

import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.closingOf
import io.github.matthewjones372.surefold.app.liquibase.Migrated
import io.github.matthewjones372.surefold.jobs.CronEntry
import io.github.matthewjones372.surefold.jobs.JobHandlers
import io.github.matthewjones372.surefold.jobs.JobSettings
import io.github.matthewjones372.surefold.jobs.JobWorker
import javax.sql.DataSource

/**
 * A [JobWorker] running [handlers], and enqueueing the firings of [cron], as a node. It takes a [Migrated], so it
 * starts only once the changelog that holds the jobs tables has run, and its [JobSettings] from the graph. Released,
 * it stops claiming and drains.
 */
fun jobWorker(handlers: JobHandlers, cron: List<CronEntry<*>> = emptyList()): Module =
    closingOf { source: DataSource, _: Migrated, settings: JobSettings ->
        JobWorker(source, handlers, settings, cron = cron).start()
    }
