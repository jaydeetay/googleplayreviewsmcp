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

class ReviewService(credentials: GoogleCredentials) {

    private val publisher = AndroidPublisher.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        HttpCredentialsAdapter(credentials)
    ).setApplicationName("google-play-reviews-mcp").build()

    fun listReviews(
        packageName: String,
        pageToken: String? = null,
        maxResults: Int = 100,
        language: String? = null,
        unansweredOnly: Boolean = false,
        searchText: String? = null,
        translationLanguage: String? = null
    ): ReviewsPage {
        val request = publisher.reviews().list(packageName)
            .setMaxResults(maxResults.toLong())
        pageToken?.let { request.setToken(it) }
        translationLanguage?.let { request.setTranslationLanguage(it) }

        val response = request.execute()
        val allReviews = (response.reviews ?: emptyList<com.google.api.services.androidpublisher.model.Review>()).map { mapReview(it) }
        val filtered = applyFilters(allReviews, language, unansweredOnly, searchText)

        return ReviewsPage(
            reviews = filtered,
            nextPageToken = response.tokenPagination?.nextPageToken
        )
    }

    fun getReview(packageName: String, reviewId: String): Review {
        val response = publisher.reviews().get(packageName, reviewId).execute()
        return mapReview(response)
    }

    fun replyToReview(packageName: String, reviewId: String, replyText: String) {
        val replyRequest = ReviewsReplyRequest().setReplyText(replyText)
        publisher.reviews().reply(packageName, reviewId, replyRequest).execute()
    }

    fun deleteReply(packageName: String, reviewId: String) {
        // The Play Developer API has no dedicated delete-reply endpoint.
        // Posting an empty string clears the reply.
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

        return Review(
            reviewId = apiReview.reviewId ?: "",
            authorName = apiReview.authorName ?: "Unknown",
            rating = userComment?.starRating ?: 0,
            reviewerLanguage = userComment?.reviewerLanguage ?: "",
            createTime = userComment?.lastModified?.seconds
                ?.let { Instant.ofEpochSecond(it).toString() } ?: "",
            updateTime = userComment?.lastModified?.seconds
                ?.let { Instant.ofEpochSecond(it).toString() } ?: "",
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
    }
}
