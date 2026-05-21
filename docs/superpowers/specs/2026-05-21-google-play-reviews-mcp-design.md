# Google Play Reviews MCP Server — Design Spec

**Date:** 2026-05-21  
**Status:** Approved

---

## Overview

A Kotlin/JVM MCP server that exposes Google Play review data and reply capabilities as MCP tools. Claude (or any MCP client) starts it as a subprocess via stdio transport — no infrastructure to manage. Designed as a Kotlin learning exercise, with stub functions and `TODO()` placeholders at key points for the developer to fill in, backed by unit tests.

---

## Architecture

```
MCP Client (Claude)
       │  stdio
       ▼
McpServer  (main entry point — registers tools, delegates to ReviewService)
       │
       ├── ReviewService  ← wraps AndroidPublisher API client
       │       ├── listReviews(...)
       │       ├── getReview(...)
       │       ├── replyToReview(...)
       │       └── deleteReply(...)
       │
       └── GoogleAuthProvider  ← loads credentials from env var
```

Three modules, each with one responsibility:

- **`GoogleAuthProvider`** — reads `GOOGLE_SERVICE_ACCOUNT_JSON` env var, produces a `GoogleCredentials` object scoped to the `androidpublisher` API. Fails fast at startup if missing or malformed.
- **`ReviewService`** — thin wrapper over the `AndroidPublisher` Java client. All Google API calls live here. Also applies client-side filtering (language, unanswered, search text).
- **`McpServer`** — registers MCP tools and wires parameters to `ReviewService` calls. Entry point for the process.

### Transport

stdio. Claude starts the server as a child process at session start and stops it when the session ends. The developer does not manage the server process. Cold-start latency is ~1–2 seconds on first use per session (JVM startup); subsequent tool calls are fast.

### Dependencies

- `io.modelcontextprotocol:kotlin-sdk` — official MCP Kotlin SDK (Ktor-based)
- `com.google.apis:google-api-services-androidpublisher` — Google Play Developer API client
- `com.google.auth:google-auth-library-oauth2-http` — service account credential handling
- `org.junit.jupiter:junit-jupiter` + `kotlin.test` — unit testing

Build system: **Gradle** with Kotlin DSL.

---

## MCP Tools

### `list_reviews`

Fetches reviews for an app with optional client-side filtering.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `packageName` | string | Yes | App package name (e.g. `com.example.app`) |
| `pageToken` | string | No | Pagination token from a previous response |
| `maxResults` | int | No | Max reviews to fetch from API (default 100, max 4096) |
| `language` | string | No | Filter to reviews where `reviewerLanguage` matches (client-side) |
| `unansweredOnly` | boolean | No | If true, exclude reviews that already have a developer reply (client-side) |
| `searchText` | string | No | Case-insensitive substring match against original text AND translated text (client-side) |
| `translationLanguage` | string | No | BCP-47 language code; API translates review text to this language |

**Returns:** `ReviewsPage`

### `get_review`

Fetches a single review by ID.

**Parameters:** `packageName` (string, required), `reviewId` (string, required)

**Returns:** `Review`

### `reply_to_review`

Posts or updates a developer reply on a review.

**Parameters:** `packageName` (string, required), `reviewId` (string, required), `replyText` (string, required)

**Returns:** Success confirmation with timestamp.

### `delete_reply`

Deletes the developer reply on a review.

**Parameters:** `packageName` (string, required), `reviewId` (string, required)

**Returns:** Success confirmation.

---

## Data Model

```kotlin
data class Review(
    val reviewId: String,
    val authorName: String,
    val rating: Int,              // 1–5
    val reviewerLanguage: String, // BCP-47
    val createTime: String,       // ISO 8601
    val updateTime: String,       // ISO 8601
    val originalText: String,
    val translatedText: String?,  // null if no translationLanguage was requested
    val deviceInfo: DeviceInfo?,
    val reply: DeveloperReply?    // null if no reply exists
)

data class DeveloperReply(
    val text: String,
    val lastModified: String      // ISO 8601
)

data class DeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val androidVersion: String?
)

data class ReviewsPage(
    val reviews: List<Review>,
    val nextPageToken: String?    // null if no further pages
)
```

---

## Auth & Configuration

| Env var | Required | Description |
|---------|----------|-------------|
| `GOOGLE_SERVICE_ACCOUNT_JSON` | Yes | Full JSON contents of a Google service account key (not a file path) |

The service account must have the **Android Publisher** API scope (`https://www.googleapis.com/auth/androidpublisher`) and be granted access to the app(s) in the Play Console.

`GoogleAuthProvider` reads the env var at startup, parses the JSON into `GoogleCredentials`, and fails fast with a clear error message if missing or invalid. Token refresh is handled automatically by the Google auth library.

---

## Client-Side Filtering

The Google Play Developer API has no server-side filtering beyond pagination and translation. All filters below are applied by `ReviewService` after the API response is received:

| Filter | Field checked | Match logic |
|--------|--------------|-------------|
| `language` | `reviewerLanguage` | Exact match (case-insensitive) |
| `unansweredOnly` | `reply` | Include only if `reply == null` |
| `searchText` | `originalText`, `translatedText` | Case-insensitive substring; passes if either field contains the string |

Filtering happens before the result is returned to the MCP client. `maxResults` is the count fetched from the API; the filtered result set may be smaller.

---

## Error Handling

| Error category | Behavior |
|---------------|----------|
| Missing/invalid `GOOGLE_SERVICE_ACCOUNT_JSON` | Fail at startup with descriptive message; server exits |
| API errors (rate limit, bad package name, insufficient permissions) | Returned as MCP tool errors with HTTP status code and Google error message |
| Invalid filter parameters (e.g. malformed language code) | Validated before API call; returned as MCP tool error with descriptive message |

No retries at the application level — the Google client library handles transient failures internally. Errors are never silently swallowed.

---

## Testing

### Unit Tests (`ReviewServiceTest`)

Cover all client-side filtering logic using hardcoded fake `Review` objects. No network calls, no credentials required. These are the primary tests for the developer to implement as a learning exercise — the test file is scaffolded with stubs and `TODO()` placeholders.

Test cases to cover:
- `language` filter matches and excludes correctly
- `unansweredOnly` excludes reviews with existing replies
- `searchText` matches against original text
- `searchText` matches against translated text
- `searchText` matches when only translated text contains the term
- `searchText` is case-insensitive
- Multiple filters applied together
- Empty result set returned when nothing matches

### Integration Tests (`GooglePlayApiIntegrationTest`)

Tagged `@Ignore` by default. Require `GOOGLE_SERVICE_ACCOUNT_JSON` and a `TEST_PACKAGE_NAME` env var to be set. Run manually to verify real API connectivity. Not part of CI.

### Test Framework

JUnit 5 with `kotlin.test` assertions.
