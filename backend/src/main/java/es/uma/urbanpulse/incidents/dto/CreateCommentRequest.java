package es.uma.urbanpulse.incidents.dto;

/**
 * {@code CreateCommentRequest}. The text is checked by {@code Comment}, not by bean validation, so
 * an incident the caller may not see answers {@code 404} before an invalid text answers {@code
 * 422}.
 */
public record CreateCommentRequest(String text) {}
