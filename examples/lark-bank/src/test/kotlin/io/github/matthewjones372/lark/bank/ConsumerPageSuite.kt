package io.github.matthewjones372.lark.bank

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.resource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Three nodes, each with its API, and a headless Chromium to open their pages in. */
internal class Browsed(val apis: List<Api>, val browser: Browser)

/** The nodes, their APIs, each crashing a node as [crash] says, and a browser, released in reverse. */
internal fun browsed(crash: (List<BankNode>) -> (String) -> Boolean = { { false } }): Resource<Browsed> = resource {
    val nodes = install({ threeNodes() }) { nodes, _ -> nodes.forEach(BankNode::close) }
    val apis = install({ nodes.map { Api(it, 0, crash = crash(nodes)) } }) { apis, _ -> apis.forEach(Api::close) }
    val playwright = install({ Playwright.create() }) { playwright, _ -> playwright.close() }
    val headless = BrowserType.LaunchOptions().setHeadless(true)
    val browser = install({ playwright.chromium().launch(headless) }) { browser, _ -> browser.close() }
    Browsed(apis, browser)
}

/** The pages in a real Chromium, headless: the only proof they work. */
internal object ConsumerPageSuite : Suite<Browsed>() {
    override val shared: Resource<Browsed> = browsed()

    init {
        test("money sent through the page arrives, and the page shows the new balance") { browsed ->
            val page = browsed.browser.newPage()
            page.navigate("http://localhost:${browsed.apis[0].port}/")
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
            // Playwright's assertions wait for the page, and throw if it never gets there: reaching here, each held.
            expect(true)
        }

        test("the page and what it loads are served, and nothing else is") { browsed ->
            val client = HttpClient.newHttpClient()
            fun get(path: String) = client.send(
                HttpRequest.newBuilder(URI("http://localhost:${browsed.apis[1].port}$path")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            fun type(path: String) = get(path).headers().firstValue("Content-Type").orElse("")
            (type("/") shouldBe "text/html; charset=utf-8") and
                (get("/consumer.js").statusCode() shouldBe 200) and
                (type("/bank.css") shouldBe "text/css; charset=utf-8") and
                (get("/../build.gradle.kts").statusCode() shouldBe 404) and
                (get("/missing.html").statusCode() shouldBe 404)
        }
    }
}
