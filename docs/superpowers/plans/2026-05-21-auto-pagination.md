# Auto-Pagination for list_reviews Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-page `list_reviews` fetch with a pagination loop that accumulates filtered results across API pages up to a caller-specified `limit`, removing the misleading `maxResults` parameter in the process.

**Architecture:** A pure `accumulateFiltered` function is added to `ReviewService.companion` — it takes pre-fetched pages and applies filters + limit logic, making it fully unit-testable without API calls. `listReviews` uses this same logic in a live loop, fetching pages from the API until `limit` filtered results are collected or pages run out. `McpServer` is updated to expose `limit` instead of `maxResults`.

**Tech Stack:** Kotlin, existing `ReviewService` + `McpServer`, JUnit 5, kotlin.test

---

## File Map

| File | Change |
|------|--------|
| `src/main/kotlin/com/googleplayreviews/ReviewService.kt` | Add `accumulateFiltered` to companion; replace `listReviews` single-page fetch with pagination loop; remove `maxResults`, add `limit` |
| `src/main/kotlin/com/googleplayreviews/McpServer.kt` | Update `list_reviews` inputSchema: remove `maxResults` property, add `limit` property; update handler to pass `limit` |
| `src/test/kotlin/com/googleplayreviews/ReviewServicePaginationTest.kt` | New: unit tests for `accumulateFiltered` covering limit, trimming, nextPageToken passthrough |

---

### Task 1: Add `accumulateFiltered` and unit tests (TDD)

**Files:**
- Modify: `src/main/kotlin/com/googleplayreviews/ReviewService.kt` (companion object, lines 116–136)
- Create: `src/test/kotlin/com/googleplayreviews/ReviewServicePaginationTest.kt`

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/googleplayreviews/ReviewServicePaginationTest.kt`:

```kotlin
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
        // page has 3 reviews but only 1 matches language — limit of 1 should not trim
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
        // 2 pages of mixed language; want 2 English reviews
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp && ./gradlew test --tests "com.googleplayreviews.ReviewServicePaginationTest"
```

Expected: FAIL — `ReviewService.accumulateFiltered` does not exist yet.

- [ ] **Step 3: Add `accumulateFiltered` to the companion object in ReviewService.kt**

In `src/main/kotlin/com/googleplayreviews/ReviewService.kt`, replace the companion object (lines 116–136) with:

```kotlin
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
        // are exhausted. Exposed here (not private) so it can be unit-tested without
        // real API calls. `listReviews` uses the same logic in a live fetching loop.
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
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp && ./gradlew test --tests "com.googleplayreviews.ReviewServicePaginationTest"
```

Expected: PASS — 7 tests.

- [ ] **Step 5: Run full test suite to confirm no regressions**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp && ./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp
git add src/main/kotlin/com/googleplayreviews/ReviewService.kt \
        src/test/kotlin/com/googleplayreviews/ReviewServicePaginationTest.kt
git commit -m "feat: add accumulateFiltered to ReviewService companion"
```

---

### Task 2: Replace `listReviews` with pagination loop

**Files:**
- Modify: `src/main/kotlin/com/googleplayreviews/ReviewService.kt` (lines 25–47)

- [ ] **Step 1: Replace the `listReviews` function**

In `src/main/kotlin/com/googleplayreviews/ReviewService.kt`, replace lines 25–47 (the entire `listReviews` function) with:

```kotlin
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
```

- [ ] **Step 2: Run full test suite**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp && ./gradlew test
```

Expected: BUILD SUCCESSFUL — all tests pass.

- [ ] **Step 3: Commit**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp
git add src/main/kotlin/com/googleplayreviews/ReviewService.kt
git commit -m "feat: replace listReviews with auto-pagination loop, remove maxResults"
```

---

### Task 3: Update McpServer — remove `maxResults`, add `limit`

**Files:**
- Modify: `src/main/kotlin/com/googleplayreviews/McpServer.kt` (lines 37–93)

- [ ] **Step 1: Replace the `registerListReviews` function**

In `src/main/kotlin/com/googleplayreviews/McpServer.kt`, replace the entire `registerListReviews` function (lines 37–94) with:

```kotlin
    private fun registerListReviews(server: Server, reviewService: ReviewService) {
        server.addTool(
            name = "list_reviews",
            description = "Fetch reviews for a Google Play app. The server auto-paginates the " +
                "Play Developer API (which returns ~12 reviews per page) until `limit` filtered " +
                "results are collected or all pages are exhausted. Filters (language, " +
                "unansweredOnly, searchText) are applied client-side; only matching reviews " +
                "count toward `limit`.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "App package name, e.g. com.example.app")
                    }
                    putJsonObject("pageToken") {
                        put("type", "string")
                        put("description", "Resume pagination from this API token (returned as nextPageToken in a previous response)")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max filtered reviews to return across all API pages (default 100, 0 = no limit)")
                    }
                    putJsonObject("language") {
                        put("type", "string")
                        put("description", "Filter by BCP-47 reviewer language (client-side)")
                    }
                    putJsonObject("unansweredOnly") {
                        put("type", "boolean")
                        put("description", "If true, only return reviews with no developer reply (client-side)")
                    }
                    putJsonObject("searchText") {
                        put("type", "string")
                        put("description", "Case-insensitive substring match against original and translated text (client-side)")
                    }
                    putJsonObject("translationLanguage") {
                        put("type", "string")
                        put("description", "BCP-47 language code; the API translates review text to this language")
                    }
                },
                required = listOf("packageName")
            )
        ) { request ->
            val args = request.arguments
            safeToolCall("list_reviews") {
                val packageName = args.requireString("packageName")
                    ?: return@safeToolCall errorResult("packageName is required")
                val page = reviewService.listReviews(
                    packageName = packageName,
                    pageToken = args.requireString("pageToken"),
                    limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 100,
                    language = args.requireString("language"),
                    unansweredOnly = args["unansweredOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    searchText = args.requireString("searchText"),
                    translationLanguage = args.requireString("translationLanguage")
                )
                textResult(json.encodeToString<ReviewsPage>(page))
            }
        }
    }
```

- [ ] **Step 2: Run full test suite**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp && ./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Rebuild the fat JAR**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp && ./gradlew jar
```

Expected: `build/libs/googleplayreviewsmcp-1.0.0.jar` rebuilt successfully.

- [ ] **Step 4: Commit and push**

```bash
cd /Users/jdt/Code/prototyping/googleplayreviewsmcp
git add src/main/kotlin/com/googleplayreviews/McpServer.kt
git commit -m "feat: update list_reviews tool — replace maxResults with limit"
git push
```

---

## Self-Review

**Spec coverage:**
- Remove `maxResults` → Tasks 2 and 3 ✓
- Add `limit` with default 100, `0` = no limit → Tasks 2 and 3 ✓
- `limit` counts filtered results → `accumulateFiltered` loop + test "language filter counts only filtered reviews toward limit" ✓
- `nextPageToken` passthrough when limit hit early → test "limit trims results and passes through the last nextPageToken" ✓
- `nextPageToken = null` when all pages exhausted → tests "single page within limit" and "accumulates across multiple pages" ✓
- `pageToken` still works for resuming → `currentToken = pageToken` in loop start ✓
- Error handling unchanged → `safeToolCall` wrapper unchanged ✓

**Placeholder scan:** None found. All code blocks complete.

**Type consistency:** `accumulateFiltered(pages: List<Pair<List<Review>, String?>>, limit: Int, ...)` defined in Task 1 companion object, tested in Task 1 tests. `listReviews(... limit: Int = 100 ...)` defined in Task 2, called in McpServer Task 3 as `limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 100`. All consistent.
