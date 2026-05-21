package com.googleplayreviews

import com.googleplayreviews.models.ReviewsPage

interface HistoricalReviewSource {
    suspend fun listReviews(
        packageName: String,
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 100,
        language: String? = null,
        unansweredOnly: Boolean = false,
        searchText: String? = null
    ): ReviewsPage
}
