/**
 * Cross-cutting HTTP plumbing shared by every controller.
 * <p>
 * {@link uk.valuationloop.api.ApiErrors} turns each module's exceptions into one small JSON error body,
 * {@code {"error": "<code>", "message": "<text>"}}, so no controller builds an error response by hand
 * (Django: one exception middleware instead of a try/except in every view).
 * {@link uk.valuationloop.api.NoSniffFilter} adds {@code X-Content-Type-Options: nosniff} to every response.
 */
package uk.valuationloop.api;
