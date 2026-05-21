package com.googleplayreviews

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsvReviewRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private data class FixtureRow(
        val reviewId: String,
        val language: String = "en",
        val device: String = "Pixel6",
        val submitDate: String = "2023-06-15T10:00:00Z",
        val rating: Int = 4,
        val text: String = "Great app",
        val replyText: String = "",
        val replyDate: String = ""
    )

    private fun writeCsv(packageName: String, yyyymm: String, rows: List<FixtureRow>) {
        val file = File(tempDir, "reviews_${packageName}_${yyyymm}.csv")
        val header = "Package Name,App Version Code,App Version Name,Reviewer Language,Device," +
            "Review Submit Date and Time,Review Submit Millis Since Epoch," +
            "Review Last Update Date and Time,Review Last Update Millis Since Epoch," +
            "Star Rating,Review Title,Review Text," +
            "Developer Reply Date and Time,Developer Reply Millis Since Epoch," +
            "Developer Reply Text,Review Link"
        val lines = mutableListOf(header)
        for (r in rows) {
            lines.add(
                "$packageName,,,${r.language},${r.device}," +
                "${r.submitDate},0,${r.submitDate},0," +
                "${r.rating},,${r.text}," +
                "${r.replyDate},0,${r.replyText}," +
                "http://play.google.com/console?reviewId=${r.reviewId}"
            )
        }
        file.writeText("﻿" + lines.joinToString("\n"), Charsets.UTF_16LE)
    }

    @Test
    fun `empty directory returns empty ReviewsPage`() = runBlocking {
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(0, result.reviews.size)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `returns mapped reviews from CSV file`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(reviewId = "abc-123", language = "en", rating = 5, text = "Love it"),
            FixtureRow(reviewId = "def-456", language = "fr", rating = 3, text = "Pas mal")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(2, result.reviews.size)
        val first = result.reviews[0]
        assertEquals("abc-123", first.reviewId)
        assertEquals("Unknown", first.authorName)
        assertEquals(5, first.rating)
        assertEquals("en", first.reviewerLanguage)
        assertEquals("Love it", first.originalText)
        assertEquals("Pixel6", first.deviceInfo?.model)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `rows without a Review Link are skipped`() = runBlocking {
        val file = File(tempDir, "reviews_com.example.app_202306.csv")
        val header = "Package Name,App Version Code,App Version Name,Reviewer Language,Device," +
            "Review Submit Date and Time,Review Submit Millis Since Epoch," +
            "Review Last Update Date and Time,Review Last Update Millis Since Epoch," +
            "Star Rating,Review Title,Review Text," +
            "Developer Reply Date and Time,Developer Reply Millis Since Epoch," +
            "Developer Reply Text,Review Link"
        val content = "$header\ncom.example.app,,,en,Pixel6,2023-06-15T10:00:00Z,0,2023-06-15T10:00:00Z,0,4,,No link,,,,"
        file.writeText("﻿$content", Charsets.UTF_16LE)
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(0, result.reviews.size)
    }

    @Test
    fun `developer reply is populated when present`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(
                reviewId = "abc-123",
                replyText = "Thanks for the feedback!",
                replyDate = "2023-06-20T09:00:00Z"
            )
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        val reply = result.reviews[0].reply
        assertEquals("Thanks for the feedback!", reply?.text)
        assertEquals("2023-06-20T09:00:00Z", reply?.lastModified)
    }

    @Test
    fun `reviews from multiple monthly files are combined`() = runBlocking {
        writeCsv("com.example.app", "202305", listOf(FixtureRow(reviewId = "may-1")))
        writeCsv("com.example.app", "202306", listOf(FixtureRow(reviewId = "jun-1")))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(2, result.reviews.size)
    }

    @Test
    fun `files for a different package are not included`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(FixtureRow(reviewId = "abc-123")))
        writeCsv("com.other.app", "202306", listOf(FixtureRow(reviewId = "other-1")))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(1, result.reviews.size)
        assertEquals("abc-123", result.reviews[0].reviewId)
    }

    @Test
    fun `startDate excludes reviews before that date`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(reviewId = "old", submitDate = "2023-06-01T00:00:00Z"),
            FixtureRow(reviewId = "new", submitDate = "2023-06-20T00:00:00Z")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", startDate = "2023-06-15")
        assertEquals(1, result.reviews.size)
        assertEquals("new", result.reviews[0].reviewId)
    }

    @Test
    fun `endDate excludes reviews after that date`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(reviewId = "early", submitDate = "2023-06-05T00:00:00Z"),
            FixtureRow(reviewId = "late", submitDate = "2023-06-25T00:00:00Z")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", endDate = "2023-06-10")
        assertEquals(1, result.reviews.size)
        assertEquals("early", result.reviews[0].reviewId)
    }

    @Test
    fun `file outside date range is skipped entirely`() = runBlocking {
        writeCsv("com.example.app", "202301", listOf(FixtureRow(reviewId = "jan")))
        writeCsv("com.example.app", "202306", listOf(FixtureRow(reviewId = "jun")))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", startDate = "2023-06-01", endDate = "2023-06-30")
        assertEquals(1, result.reviews.size)
        assertEquals("jun", result.reviews[0].reviewId)
    }

    @Test
    fun `limit stops reading after enough reviews are collected`() = runBlocking {
        writeCsv("com.example.app", "202305", listOf(
            FixtureRow(reviewId = "r1"), FixtureRow(reviewId = "r2"), FixtureRow(reviewId = "r3")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", limit = 2)
        assertEquals(2, result.reviews.size)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `limit zero returns all reviews`() = runBlocking {
        writeCsv("com.example.app", "202305", listOf(
            FixtureRow(reviewId = "r1"), FixtureRow(reviewId = "r2"), FixtureRow(reviewId = "r3")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", limit = 0)
        assertEquals(3, result.reviews.size)
    }
}
