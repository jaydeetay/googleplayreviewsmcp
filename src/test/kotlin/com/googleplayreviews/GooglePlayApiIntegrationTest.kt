package com.googleplayreviews

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Disabled("Requires GOOGLE_SERVICE_ACCOUNT_JSON and TEST_PACKAGE_NAME env vars. Run manually only.")
class GooglePlayApiIntegrationTest {

    private val packageName = System.getenv("TEST_PACKAGE_NAME")
        ?: error("TEST_PACKAGE_NAME env var not set")

    private val reviewService = ReviewService(GoogleAuthProvider.fromEnv())

    @Test
    fun `can list reviews`() = runBlocking {
        val page = reviewService.listReviews(packageName, limit = 5)
        assertNotNull(page)
        assertTrue(page.reviews.isNotEmpty(), "Expected at least one review")
    }

    @Test
    fun `can get a single review`() = runBlocking {
        val page = reviewService.listReviews(packageName, limit = 1)
        val reviewId = page.reviews.first().reviewId
        val review = reviewService.getReview(packageName, reviewId)
        assertNotNull(review)
        assertTrue(review.reviewId == reviewId)
    }

    @Test
    fun `language filter returns only matching reviews`() = runBlocking {
        val page = reviewService.listReviews(packageName, limit = 50, language = "en")
        assertTrue(
            page.reviews.all { it.reviewerLanguage.equals("en", ignoreCase = true) },
            "All reviews should have reviewerLanguage=en"
        )
    }
}
