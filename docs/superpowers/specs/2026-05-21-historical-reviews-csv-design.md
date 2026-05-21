# Historical Reviews — Local CSV Support

**Date:** 2026-05-21  
**Status:** Approved

## Problem

The Google Play Developer API only returns reviews from the last 7 days. Full review history is available as monthly CSV reports downloadable from Google Cloud Storage via `gsutil`. This design adds a new MCP tool that reads those locally-downloaded files.

## Decisions

- Data source location: `PLAY_REVIEWS_DIR` environment variable (configurable per project, consistent with credential config pattern)
- Separate `list_historical_reviews` tool (not merged into `list_reviews` — different data characteristics and filter set)
- Thin `HistoricalReviewSource` interface so a future `GcsReviewRepository` can slot in without touching `McpServer`
- Reviews with no Review Link (no recoverable reviewId) are skipped
- Review Title column is always empty in practice — ignored

## Architecture

| Component | Responsibility |
|---|---|
| `HistoricalReviewSource` | Interface: `listReviews(...)` → `ReviewsPage` |
| `CsvReviewRepository` | Implements interface; discovers and parses local CSV files |
| `McpServer` | Registers `list_historical_reviews` against `HistoricalReviewSource?` (nullable — tool absent if env var unset) |
| `Main.kt` | Reads `PLAY_REVIEWS_DIR`; instantiates `CsvReviewRepository` if present |

## `HistoricalReviewSource` Interface

```kotlin
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
```

## `CsvReviewRepository`

### File Discovery

- Globs `$PLAY_REVIEWS_DIR/reviews_<packageName>_<YYYYMM>.csv`
- Month-level pre-filter: files whose `YYYYMM` falls entirely outside `[startDate, endDate]` are skipped before opening

### CSV Format

- Encoding: UTF-16LE with BOM (`ff fe`)
- One file per calendar month
- ~2000–2500 rows per file typical

### Column Mapping

| CSV Column | Review Field |
|---|---|
| Reviewer Language | `reviewerLanguage` |
| Device | `DeviceInfo.model` |
| Review Submit Date and Time | `createTime` |
| Review Last Update Date and Time | `updateTime` |
| Star Rating | `rating` |
| Review Text | `originalText` |
| Developer Reply Text + Date | `reply` (null if both empty) |
| Review Link | extract `reviewId` query param |
| *(no author name)* | `authorName = "Unknown"` |
| Review Title | ignored (always empty in practice) |

### Row Skipping

- Rows with no Review Link (no recoverable `reviewId`) are skipped silently
- Malformed rows are skipped with a debug-level log; parsing continues

### Filtering

Reuses `ReviewService.applyFilters()` for `language`, `unansweredOnly`, and `searchText` after CSV parsing. Date range filtering is applied per-row by parsing the `Review Submit Date and Time` ISO string (e.g. `2013-08-01T01:51:48Z`) and comparing against `startDate`/`endDate`.

## `list_historical_reviews` MCP Tool

### Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `packageName` | string | yes | |
| `startDate` | string | no | ISO date `"2023-01-01"`, inclusive |
| `endDate` | string | no | ISO date `"2023-12-31"`, inclusive |
| `limit` | integer | no | default 100; 0 = no limit |
| `language` | string | no | BCP-47 reviewer language filter |
| `unansweredOnly` | boolean | no | exclude reviews with a developer reply |
| `searchText` | string | no | case-insensitive substring match |

### Response

Same `ReviewsPage` shape as `list_reviews`. `nextPageToken` is always null (CSV reads are not paginated).

### Registration

Tool is only registered when `PLAY_REVIEWS_DIR` is set and resolves to an existing directory. If absent or invalid, the tool does not appear in the server's tool list.

## Error Handling

| Condition | Behaviour |
|---|---|
| `PLAY_REVIEWS_DIR` unset | Tool not registered |
| `PLAY_REVIEWS_DIR` does not exist | Warn at startup, tool not registered |
| No CSV files found for `packageName` | Return empty `ReviewsPage` |
| Invalid `startDate`/`endDate` format | Return tool error immediately |
| Malformed CSV row | Skip row, log at debug level, continue |

## Testing

- `CsvReviewRepository` tested with real fixture CSV files (small slice of actual data) — no credentials or API mocks needed
- Filter logic not re-tested (`ReviewService.applyFilters()` already has coverage)
- `McpServer` tests inject a fake `HistoricalReviewSource` — no filesystem dependency

## Future: GCS Support

A `GcsReviewRepository` implementing `HistoricalReviewSource` would read directly from `gs://pubsite_prod_rev_<bucket>/reviews/`. `McpServer` registration would not change. Selection between CSV and GCS would be via a different env var or a `source` config param (TBD in that design).
