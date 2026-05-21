package com.googleplayreviews

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertTrue

class GoogleAuthProviderTest {

    @Test
    fun `throws when env var is missing`() {
        val exception = assertThrows<IllegalStateException> {
            GoogleAuthProvider.loadCredentials(envVarValue = null)
        }
        assertTrue(
            exception.message!!.contains("GOOGLE_SERVICE_ACCOUNT_JSON"),
            "Error message should mention the env var name"
        )
    }

    @Test
    fun `throws when env var contains malformed JSON`() {
        assertThrows<IllegalArgumentException> {
            GoogleAuthProvider.loadCredentials(envVarValue = "not-valid-json")
        }
    }
}
