package io.github.matthewjones372.lark.bank

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The pages in a real Chromium, headless: the only proof they work. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPageTest {
    private val nodes = threeNodes()
    private val apis = nodes.map { Api(it, 0) }
    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))

    @Test
    fun `money sent through the page arrives, and the page shows the new balance`() {
        val page = browser.newPage()
        page.navigate("http://localhost:${apis[0].port}/")
        page.fill("#account-id", "bob")
        page.fill("#open-amount", "20")
        page.click("#open")
        assertThat(page.locator("#balance")).hasText("20")
        page.fill("#account-id", "alice")
        page.fill("#open-amount", "500")
        page.click("#open")
        assertThat(page.locator("#balance")).hasText("500")

        page.fill("#to", "bob")
        page.fill("#amount", "120")
        page.click("#send")

        assertThat(page.locator("#transfer-status")).hasText("Done")
        assertThat(page.locator("#balance")).hasText("380")
        assertThat(page.locator("#movements tbody tr")).hasCount(1)
        page.fill("#account-id", "bob")
        page.click("#show")
        assertThat(page.locator("#balance")).hasText("140")
    }

    @Test
    fun `the page and what it loads are served, and nothing else is`() {
        val client = HttpClient.newHttpClient()
        fun get(path: String) = client.send(
            HttpRequest.newBuilder(URI("http://localhost:${apis[1].port}$path")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        get("/").headers().firstValue("Content-Type").orElse("") shouldBe "text/html; charset=utf-8"
        get("/consumer.js").statusCode() shouldBe 200
        get("/bank.css").headers().firstValue("Content-Type").orElse("") shouldBe "text/css; charset=utf-8"
        get("/../build.gradle.kts").statusCode() shouldBe 404
        get("/missing.html").statusCode() shouldBe 404
    }

    @AfterAll
    fun close() {
        browser.close()
        playwright.close()
        apis.forEach(Api::close)
        nodes.forEach(BankNode::close)
    }
}
