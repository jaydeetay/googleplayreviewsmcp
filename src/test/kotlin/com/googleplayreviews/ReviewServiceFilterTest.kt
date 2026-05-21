package com.googleplayreviews

import com.googleplayreviews.models.DeveloperReply
import com.googleplayreviews.models.Review
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewServiceFilterTest {

    private val sampleReply = DeveloperReply(
        text = "Thanks!",
        lastModified = "2024-01-02T00:00:00Z"
    )

    private fun fakeReview(
        reviewId: String = "r1",
        language: String = "en",
        originalText: String = "Great app",
        translatedText: String? = null,
        reply: DeveloperReply? = null
    ) = Review(
        reviewId = reviewId,
        authorName = "Test User",
        rating = 5,
        reviewerLanguage = language,
        createTime = "2024-01-01T00:00:00Z",
        updateTime = "2024-01-01T00:00:00Z",
        originalText = originalText,
        translatedText = translatedText,
        reply = reply
    )

    @Test
    fun `language filter includes matching reviews`() {
        val reviews = listOf(fakeReview(language = "en"), fakeReview(language = "fr"))
        val result = ReviewService.applyFilters(reviews, language = "en")
        assertEquals(1, result.size)
        assertEquals("en", result[0].reviewerLanguage)
    }

    @Test
    fun `language filter is case-insensitive`() {
        val reviews = listOf(fakeReview(language = "EN"))
        val result = ReviewService.applyFilters(reviews, language = "en")
        assertEquals(1, result.size)
    }

    @Test
    fun `language filter null passes all reviews through`() {
        val reviews = listOf(fakeReview(language = "en"), fakeReview(language = "fr"))
        val result = ReviewService.applyFilters(reviews, language = null)
        assertEquals(2, result.size)
    }

    @Test
    fun `unansweredOnly excludes reviews with replies`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", reply = null),
            fakeReview(reviewId = "r2", reply = sampleReply)
        )
        val result = ReviewService.applyFilters(reviews, unansweredOnly = true)
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `unansweredOnly false passes all reviews through`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", reply = null),
            fakeReview(reviewId = "r2", reply = sampleReply)
        )
        val result = ReviewService.applyFilters(reviews, unansweredOnly = false)
        assertEquals(2, result.size)
    }

    @Test
    fun `searchText matches against original text`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", originalText = "I love this app"),
            fakeReview(reviewId = "r2", originalText = "This is terrible")
        )
        val result = ReviewService.applyFilters(reviews, searchText = "love")
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `searchText matches against translated text when original does not match`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", originalText = "J'adore", translatedText = "I love this"),
            fakeReview(reviewId = "r2", originalText = "C'est nul", translatedText = "This is bad")
        )
        val result = ReviewService.applyFilters(reviews, searchText = "love")
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `searchText is case-insensitive`() {
        val reviews = listOf(fakeReview(originalText = "Great App"))
        val result = ReviewService.applyFilters(reviews, searchText = "great app")
        assertEquals(1, result.size)
    }

    @Test
    fun `searchText null passes all reviews through`() {
        val reviews = listOf(fakeReview(originalText = "anything"))
        val result = ReviewService.applyFilters(reviews, searchText = null)
        assertEquals(1, result.size)
    }

    @Test
    fun `multiple filters applied together`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", language = "en", originalText = "love it", reply = null),
            fakeReview(reviewId = "r2", language = "en", originalText = "love it", reply = sampleReply),
            fakeReview(reviewId = "r3", language = "fr", originalText = "love it", reply = null),
            fakeReview(reviewId = "r4", language = "en", originalText = "hate it", reply = null)
        )
        val result = ReviewService.applyFilters(
            reviews,
            language = "en",
            unansweredOnly = true,
            searchText = "love"
        )
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `returns empty list when nothing matches`() {
        val reviews = listOf(fakeReview(originalText = "great"))
        val result = ReviewService.applyFilters(reviews, searchText = "terrible")
        assertEquals(0, result.size)
    }
}
