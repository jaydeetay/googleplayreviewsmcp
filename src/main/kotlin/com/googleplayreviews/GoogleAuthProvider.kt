package com.googleplayreviews

import com.google.auth.oauth2.GoogleCredentials
import java.io.ByteArrayInputStream

object GoogleAuthProvider {

    private const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"

    fun fromEnv(): GoogleCredentials {
        val json = System.getenv("GOOGLE_SERVICE_ACCOUNT_JSON")
        return loadCredentials(json)
    }

    fun loadCredentials(envVarValue: String?): GoogleCredentials {
        checkNotNull(envVarValue) {
            "GOOGLE_SERVICE_ACCOUNT_JSON environment variable is not set"
        }
        return try {
            GoogleCredentials
                .fromStream(ByteArrayInputStream(envVarValue.toByteArray()))
                .createScoped(listOf(SCOPE))
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "GOOGLE_SERVICE_ACCOUNT_JSON contains invalid content: ${e.message}", e
            )
        }
    }
}
