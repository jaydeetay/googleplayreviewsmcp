# An MCP server to read and reply to Google Play Reviews

NOTE: Google's APIs truly suck - this one only returns reviews from the last 7 days.  They have some weak justification, but it's really just crap on their part. They make little effort to document it, but there is this:

https://support.google.com/googleplay/android-developer/thread/239806165/google-play-api-list-reviews-only-returns-last-7-days-of-reviews-and-does-not-return-pagination

That said, if you can obtain the review ID you can still use the API to read it and to post replies even to old reviews.  The MCP server can deal with this by being pointed at a dump of the csv files you can download from the Play Store.  TODO: make it just get them from Cloud.

Needs a jvm as it's kotlin.

* TODO: consider other binary options
* TODO: post usage instructions
