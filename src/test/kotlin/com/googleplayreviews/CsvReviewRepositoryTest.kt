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
}
