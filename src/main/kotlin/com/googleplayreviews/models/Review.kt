package com.googleplayreviews.models

import kotlinx.serialization.Serializable

@Serializable
data class Review(
    val reviewId: String,
    val authorName: String,
    val rating: Int,
    val reviewerLanguage: String,
    val createTime: String,
    val updateTime: String,
    val originalText: String,
    val translatedText: String? = null,
    val deviceInfo: DeviceInfo? = null,
    val reply: DeveloperReply? = null
)

@Serializable
data class DeveloperReply(
    val text: String,
    val lastModified: String
)

@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val androidVersion: String? = null
)

@Serializable
data class ReviewsPage(
    val reviews: List<Review>,
    val nextPageToken: String? = null
)
