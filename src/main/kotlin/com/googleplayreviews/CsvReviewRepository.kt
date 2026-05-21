package com.googleplayreviews

import com.googleplayreviews.models.ReviewsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        ReviewsPage(emptyList())
    }
}
