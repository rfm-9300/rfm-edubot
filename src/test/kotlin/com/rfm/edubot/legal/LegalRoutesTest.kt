package com.rfm.edubot.legal

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LegalRoutesTest {

    @Test
    fun `the privacy policy carries what Google's review of the Gmail scopes checks for`() = testApplication {
        application { routing { legalRoutes() } }

        val response = client.get("/privacy")
        val page = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        // Google asks for this sentence as written, linking its policy.
        assertContains(
            page.replace(Regex("\\s+"), " "),
            "use and transfer to any other app of information received from Google APIs will adhere to the " +
                "<a href=\"https://developers.google.com/terms/api-services-user-data-policy\">Google API Services User Data Policy</a>, " +
                "including the Limited Use requirements",
        )
        assertContains(page, "gmail.send")
        assertContains(page, "train")
        assertContains(page, "90 days")
    }

    @Test
    fun `the privacy policy and the deletion page cover a company's own address and who receives its mail`() = testApplication {
        application { routing { legalRoutes() } }

        val privacy = client.get("/privacy").bodyAsText().replace(Regex("\\s+"), " ")
        assertContains(privacy, "id=\"mailbox\"")
        assertContains(privacy, "Amazon SES and Amazon S3, in the European Union (Ireland)")
        assertContains(privacy, "removing the address from the dashboard deletes all the email kept from it")
        val deletion = client.get("/data-deletion").bodyAsText()
        assertContains(deletion, "Remove the business&rsquo;s email address")
    }

    @Test
    fun `the data deletion page says how to remove a Google account`() = testApplication {
        application { routing { legalRoutes() } }

        val page = client.get("/data-deletion").bodyAsText()

        assertContains(page, "Disconnect a Google (Gmail) account")
        assertContains(page, "https://myaccount.google.com/permissions")
    }
}
