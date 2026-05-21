package com.googleplayreviews

import com.googleplayreviews.models.Review
import com.googleplayreviews.models.ReviewsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewServicePaginationTest {

    private fun fakeReview(reviewId: String, language: String = "en") = Review(
        reviewId = reviewId,
        authorName = "User",
        rating = 4,
        reviewerLanguage = language,
        createTime = "2024-01-01T00:00:00Z",
        updateTime = "2024-01-01T00:00:00Z",
        originalText = "Good app"
    )

    @Test
    fun `single page within limit returns all reviews and null nextPageToken`() {
        val pages = listOf(
            listOf(fakeReview("r1"), fakeReview("r2")) to null
        )
        val result = ReviewService.accumulateFiltered(pages, limit = 10)
        assertEquals(2, result.reviews.size)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `limit trims results and passes through the last nextPageToken`() {
        val pages = listOf(
            listOf(fakeReview("r1"), fakeReview("r2"), fakeReview("r3")) to "token-page2",
            listOf(fakeReview("r4")) to null
        )
        val result = ReviewService.accumulateFiltered(pages, limit = 2)
        assertEquals(2, result.reviews.size)
        assertEquals(listOf("r1", "r2"), result.reviews.map { it.reviewId })
        assertEquals("token-page2", result.nextPageToken)
    }

    @Test
    fun `limit reached exactly on page boundary returns null nextPageToken`() {
        val pages = listOf(
            listOf(fakeReview("r1"), fakeReview("r2")) to null
        )
        val result = ReviewService.accumulateFiltered(pages, limit = 2)
        assertEquals(2, result.reviews.size)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `limit zero returns all reviews from all pages`() {
        val pages = listOf(
            listOf(fakeReview("r1"), fakeReview("r2")) to "token",
            listOf(fakeReview("r3"), fakeReview("r4")) to null
        )
        val result = ReviewService.accumulateFiltered(pages, limit = 0)
        assertEquals(4, result.reviews.size)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `accumulates across multiple pages before hitting limit`() {
        val pages = listOf(
            listOf(fakeReview("r1")) to "t1",
            listOf(fakeReview("r2")) to "t2",
            listOf(fakeReview("r3")) to null
        )
        val result = ReviewService.accumulateFiltered(pages, limit = 3)
        assertEquals(3, result.reviews.size)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `language filter counts only filtered reviews toward limit`() {
        val pages = listOf(
            listOf(fakeReview("r1", "en"), fakeReview("r2", "fr"), fakeReview("r3", "fr")) to null
        )
        val result = ReviewService.accumulateFiltered(pages, limit = 1, language = "en")
        assertEquals(1, result.reviews.size)
        assertEquals("r1", result.reviews[0].reviewId)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `limit applies to filtered count spanning pages`() {
        val pages = listOf(
            listOf(fakeReview("r1", "en"), fakeReview("r2", "fr")) to "t1",
            listOf(fakeReview("r3", "en"), fakeReview("r4", "fr")) to null
        )
        val result = ReviewService.accumulateFiltered(pages, limit = 2, language = "en")
        assertEquals(2, result.reviews.size)
        assertEquals(listOf("r1", "r3"), result.reviews.map { it.reviewId })
        assertNull(result.nextPageToken)
    }
}
