package io.github.matthewjones372.lark.bank

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminPageTest {
    private val nodes = threeNodes()

    // What the admin's crash button does here: the node goes as a crash does, on a thread of its own.
    private val crash = { name: String ->
        val node = nodes.firstOrNull { it.name == name }
        node?.let { Thread.ofVirtual().start(it::close) }
        node != null
    }
    private val apis = nodes.map { Api(it, 0, crash = crash) }
    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    private val within = LocatorAssertions.HasTextOptions().setTimeout(30_000.0)

    @Test
    @Timeout(120)
    fun `the load raises the transfers-a-second tile, and a crashed n3's row turns unreachable`() {
        val page = browser.newPage()
        page.navigate("http://localhost:${apis[0].port}/admin")
        assertThat(page.locator("#node-n3 .status")).hasText("Up", within)

        page.click("#load")
        assertThat(page.locator("#load")).hasText("Stop load")
        page.waitForFunction(
            "() => Number(document.getElementById('tps').textContent) > 0",
            null,
            Page.WaitForFunctionOptions().setTimeout(30_000.0),
        )
        page.click("#crash")

        assertThat(page.locator("#node-n3 .status")).hasText("Unreachable", within)
        page.click("#load")
        assertThat(page.locator("#load")).hasText("Start load")
    }

    @AfterAll
    fun close() {
        browser.close()
        playwright.close()
        apis.forEach(Api::close)
        nodes.forEach(BankNode::close)
    }
}
