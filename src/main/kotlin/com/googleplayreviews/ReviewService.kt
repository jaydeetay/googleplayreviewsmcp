package com.googleplayreviews

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.ReviewsReplyRequest
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.googleplayreviews.models.DeveloperReply
import com.googleplayreviews.models.DeviceInfo
import com.googleplayreviews.models.Review
import com.googleplayreviews.models.ReviewsPage
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReviewService(credentials: GoogleCredentials) {

    private val publisher = AndroidPublisher.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        HttpCredentialsAdapter(credentials)
    ).setApplicationName("google-play-reviews-mcp").build()

    suspend fun listReviews(
        packageName: String,
        pageToken: String? = null,
        limit: Int = 100,
        language: String? = null,
        unansweredOnly: Boolean = false,
        searchText: String? = null,
        translationLanguage: String? = null
    ): ReviewsPage = withContext(Dispatchers.IO) {
        val accumulated = mutableListOf<Review>()
        var currentToken = pageToken

        while (true) {
            val request = publisher.reviews().list(packageName)
            currentToken?.let { request.setToken(it) }
            translationLanguage?.let { request.setTranslationLanguage(it) }

            val response = request.execute()
            val rawReviews = (response.reviews
                ?: emptyList<com.google.api.services.androidpublisher.model.Review>())
                .map { mapReview(it) }
            val apiNextToken = response.tokenPagination?.nextPageToken

            accumulated.addAll(applyFilters(rawReviews, language, unansweredOnly, searchText))

            if (limit > 0 && accumulated.size >= limit) {
                return@withContext ReviewsPage(accumulated.take(limit), nextPageToken = apiNextToken)
            }

            if (apiNextToken == null) break
            currentToken = apiNextToken
        }

        ReviewsPage(reviews = accumulated, nextPageToken = null)
    }

    suspend fun getReview(packageName: String, reviewId: String): Review = withContext(Dispatchers.IO) {
        val response = publisher.reviews().get(packageName, reviewId).execute()
        mapReview(response)
    }

    suspend fun replyToReview(packageName: String, reviewId: String, replyText: String): Unit = withContext(Dispatchers.IO) {
        val replyRequest = ReviewsReplyRequest().setReplyText(replyText)
        publisher.reviews().reply(packageName, reviewId, replyRequest).execute()
    }

    suspend fun deleteReply(packageName: String, reviewId: String) {
        // The Play Developer API client library (v3-rev20241217) does not expose a
        // deleteReply endpoint; posting an empty string is the documented workaround.
        replyToReview(packageName, reviewId, "")
    }

    private fun mapReview(
        apiReview: com.google.api.services.androidpublisher.model.Review
    ): Review {
        val userComment = apiReview.comments
            ?.firstOrNull { it.userComment != null }
            ?.userComment

        val developerComment = apiReview.comments
            ?.firstOrNull { it.developerComment != null }
            ?.developerComment

        val metadata = userComment?.deviceMetadata

        // When translationLanguage is requested, the API puts translated text in `text`
        // and the original in `originalText`. Without translation, `originalText` is null
        // and `text` contains the original.
        val originalText = userComment?.originalText ?: userComment?.text ?: ""
        val translatedText = if (userComment?.originalText != null) userComment.text else null

        // The Play Developer API exposes only `lastModified` on the user comment,
        // which reflects the most recent edit time. There is no separate creation
        // timestamp available via this API; both fields carry the same value.
        val lastModifiedIso = userComment?.lastModified?.seconds
            ?.let { Instant.ofEpochSecond(it).toString() } ?: ""

        return Review(
            reviewId = apiReview.reviewId ?: "",
            authorName = apiReview.authorName ?: "Unknown",
            rating = userComment?.starRating ?: 0,
            reviewerLanguage = userComment?.reviewerLanguage ?: "",
            createTime = lastModifiedIso,
            updateTime = lastModifiedIso,
            originalText = originalText,
            translatedText = translatedText,
            deviceInfo = metadata?.let {
                DeviceInfo(
                    manufacturer = it.manufacturer,
                    model = it.productName,
                    androidVersion = userComment?.androidOsVersion?.toString()
                )
            },
            reply = developerComment?.let {
                DeveloperReply(
                    text = it.text ?: "",
                    lastModified = it.lastModified?.seconds
                        ?.let { s -> Instant.ofEpochSecond(s).toString() } ?: ""
                )
            }
        )
    }

    companion object {
        fun applyFilters(
            reviews: List<Review>,
            language: String? = null,
            unansweredOnly: Boolean = false,
            searchText: String? = null
        ): List<Review> = reviews.filter { review ->
            val languageMatch = language == null ||
                review.reviewerLanguage.equals(language, ignoreCase = true)

            val unansweredMatch = !unansweredOnly || review.reply == null

            val searchMatch = searchText == null || run {
                val term = searchText.lowercase()
                review.originalText.lowercase().contains(term) ||
                    review.translatedText?.lowercase()?.contains(term) == true
            }

            languageMatch && unansweredMatch && searchMatch
        }

        // Pure function: given pre-fetched pages of raw reviews (each paired with its
        // nextPageToken), accumulates filtered results until `limit` is reached or pages
        // are exhausted. Exposed here so it can be unit-tested without real API calls.
        // `listReviews` uses the same logic in a live fetching loop.
        fun accumulateFiltered(
            pages: List<Pair<List<Review>, String?>>,
            limit: Int,
            language: String? = null,
            unansweredOnly: Boolean = false,
            searchText: String? = null
        ): ReviewsPage {
            val accumulated = mutableListOf<Review>()
            for ((rawReviews, nextToken) in pages) {
                accumulated.addAll(applyFilters(rawReviews, language, unansweredOnly, searchText))
                if (limit > 0 && accumulated.size >= limit) {
                    return ReviewsPage(accumulated.take(limit), nextPageToken = nextToken)
                }
                if (nextToken == null) return ReviewsPage(accumulated, nextPageToken = null)
            }
            return ReviewsPage(accumulated, nextPageToken = null)
        }
    }
}
