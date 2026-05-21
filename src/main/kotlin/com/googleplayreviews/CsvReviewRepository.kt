package com.googleplayreviews

import com.googleplayreviews.models.DeviceInfo
import com.googleplayreviews.models.DeveloperReply
import com.googleplayreviews.models.Review
import com.googleplayreviews.models.ReviewsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.time.LocalDate

class CsvReviewRepository(private val reviewsDir: File) : HistoricalReviewSource {

    override suspend fun listReviews(
        packageName: String,
        startDate: String?,
        endDate: String?,
        limit: Int,
        language: String?,
        unansweredOnly: Boolean,
        searchText: String?
    ): ReviewsPage = withContext(Dispatchers.IO) {
        val start = startDate?.let { LocalDate.parse(it) }
        val end = endDate?.let { LocalDate.parse(it) }

        val files = reviewsDir.listFiles { f ->
            f.name.matches(Regex("reviews_${Regex.escape(packageName)}_\\d{6}\\.csv"))
        }?.sortedBy { it.name } ?: emptyList()

        val accumulated = mutableListOf<Review>()

        for (file in files) {
            val yyyymm = file.nameWithoutExtension.substringAfterLast("_")
            if (!monthInRange(yyyymm, start, end)) continue

            val rows = parseCsv(file)
            val reviews = rows.mapNotNull { mapRow(it) }
                .filter { rowInDateRange(it.createTime, start, end) }

            accumulated.addAll(ReviewService.applyFilters(reviews, language, unansweredOnly, searchText))

            if (limit > 0 && accumulated.size >= limit) {
                return@withContext ReviewsPage(accumulated.take(limit), nextPageToken = null)
            }
        }

        ReviewsPage(accumulated, nextPageToken = null)
    }

    private fun monthInRange(yyyymm: String, start: LocalDate?, end: LocalDate?): Boolean {
        val year = yyyymm.substring(0, 4).toInt()
        val month = yyyymm.substring(4, 6).toInt()
        val monthStart = LocalDate.of(year, month, 1)
        val monthEnd = monthStart.plusMonths(1).minusDays(1)
        if (start != null && monthEnd.isBefore(start)) return false
        if (end != null && monthStart.isAfter(end)) return false
        return true
    }

    private fun rowInDateRange(createTime: String, start: LocalDate?, end: LocalDate?): Boolean {
        if (start == null && end == null) return true
        return try {
            val date = LocalDate.parse(createTime.substringBefore("T"))
            (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
        } catch (e: Exception) {
            false
        }
    }

    private fun parseCsv(file: File): List<Map<String, String>> {
        val raw = file.readText(Charsets.UTF_16LE)
        val content = if (raw.startsWith('﻿')) raw.substring(1) else raw
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val headers = parseCsvRow(lines[0])
        return lines.drop(1).mapNotNull { line ->
            val values = parseCsvRow(line)
            if (values.size == headers.size) headers.zip(values).toMap() else null
        }
    }

    private fun parseCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i += 2
                    continue
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun mapRow(row: Map<String, String>): Review? {
        val reviewId = extractReviewId(row["Review Link"].orEmpty()) ?: return null
        val replyText = row["Developer Reply Text"].orEmpty()
        val replyDate = row["Developer Reply Date and Time"].orEmpty()
        return Review(
            reviewId = reviewId,
            authorName = "Unknown", // CSV exports do not include reviewer name
            rating = row["Star Rating"]?.toIntOrNull() ?: 0,
            reviewerLanguage = row["Reviewer Language"].orEmpty(),
            createTime = row["Review Submit Date and Time"].orEmpty(),
            updateTime = row["Review Last Update Date and Time"].orEmpty(),
            originalText = row["Review Text"].orEmpty(),
            deviceInfo = row["Device"]?.takeIf { it.isNotBlank() }?.let { DeviceInfo(model = it) },
            reply = if (replyText.isNotBlank()) DeveloperReply(text = replyText, lastModified = replyDate) else null
        )
    }

    private fun extractReviewId(reviewLink: String): String? {
        if (reviewLink.isBlank()) return null
        return try {
            URI(reviewLink).query
                ?.split("&")
                ?.firstOrNull { it.startsWith("reviewId=") }
                ?.substringAfter("reviewId=")
        } catch (e: Exception) {
            null
        }
    }
}
