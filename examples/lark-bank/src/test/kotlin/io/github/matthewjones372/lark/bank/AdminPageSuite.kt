package io.github.matthewjones372.lark.bank

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.holds
import io.github.matthewjones372.lark.junit.timeout
import kotlin.time.Duration.Companion.seconds

private val within = LocatorAssertions.HasTextOptions().setTimeout(30_000.0)

internal object AdminPageSuite : SharedSuite<Browsed>() {
    // What the admin's crash button does here: the node goes as a crash does, on a thread of its own.
    override val shared: Resource<Browsed> = browsed { nodes ->
        { name ->
            val node = nodes.firstOrNull { it.name == name }
            node?.let { Thread.ofVirtual().start(it::close) }
            node != null
        }
    }

    init {
        test(
            "the load raises the transfers-a-second tile, and a crashed n3's row turns unreachable",
            timeout(120.seconds),
        ) { browsed ->
            val page = browsed.browser.newPage()
            page.navigate("http://localhost:${browsed.apis[0].port}/admin")
            val upBefore = holds { assertThat(page.locator("#node-n3 .status")).hasText("Up", within) }

            page.click("#load")
            val loading = holds { assertThat(page.locator("#load")).hasText("Stop load") }
            page.waitForFunction(
                "() => Number(document.getElementById('tps').textContent) > 0",
                null,
                Page.WaitForFunctionOptions().setTimeout(30_000.0),
            )
            page.click("#crash")

            val unreachable = holds { assertThat(page.locator("#node-n3 .status")).hasText("Unreachable", within) }
            page.click("#load")
            val stopped = holds { assertThat(page.locator("#load")).hasText("Start load") }
            upBefore and loading and unreachable and stopped
        }
    }
}
