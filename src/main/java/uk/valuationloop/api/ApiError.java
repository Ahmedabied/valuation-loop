package uk.valuationloop.api;

/**
 * The one error body every endpoint returns: a short machine code and a human message.
 * Messages name ids at most (lead, agent, pack, request); they never echo free text the caller sent.
 */
public record ApiError(String error, String message) {}
