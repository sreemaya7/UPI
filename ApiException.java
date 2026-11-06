/** An error that is safe to show to the user (validation, auth, limits). */
final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status;
    final long retryAfterSeconds;

    ApiException(int status, String message) { this(status, message, 0); }

    ApiException(int status, String message, long retryAfterSeconds) {
        super(message, null, false, false);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
