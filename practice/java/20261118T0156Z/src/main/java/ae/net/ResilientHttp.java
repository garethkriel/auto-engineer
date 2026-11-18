package ae.net;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Wraps an {@link HttpClient} with safe retries (idempotent requests only), Retry-After and a delay budget. */
public final class ResilientHttp {
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 502, 503, 504);
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");

    private final HttpClient client;
    private final RetryPolicy policy;
    private final Sleeper sleeper;

    public ResilientHttp(HttpClient client, RetryPolicy policy, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client, "client");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** Sends the request, retrying per policy. See TASK.md for the rules. */
    public HttpResult send(HttpRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        boolean canRetry = isRetrySafe(request);
        Duration slept = Duration.ZERO;
        IOException lastFailure = null;
        List<IOException> earlier = new ArrayList<>();
        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse<String> response = null;
            Duration retryAfter = null;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (!RETRYABLE_STATUS.contains(response.statusCode())) {
                    return new HttpResult(response.statusCode(), response.body(), attempt, slept);
                }
                retryAfter = parseRetryAfter(response).orElse(null);
            } catch (IOException e) {
                if (lastFailure != null) earlier.add(lastFailure);
                lastFailure = e;
            }
            Duration delay = retryAfter != null ? min(retryAfter, policy.maxDelay()) : policy.delayFor(attempt);
            boolean giveUp = !canRetry || attempt >= policy.maxAttempts()
                    || slept.plus(delay).compareTo(policy.totalBudget()) > 0;
            if (giveUp) {
                if (response != null) {
                    return new HttpResult(response.statusCode(), response.body(), attempt, slept);
                }
                for (IOException prior : earlier) lastFailure.addSuppressed(prior);
                throw lastFailure;
            }
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            slept = slept.plus(delay);
        }
    }

    private static boolean isRetrySafe(HttpRequest request) {
        return IDEMPOTENT_METHODS.contains(request.method())
                || request.headers().firstValue("Idempotency-Key").isPresent();
    }

    /** Only the integer-seconds form is honoured; anything else yields empty. */
    private static Optional<Duration> parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(v -> {
            String s = v.trim();
            if (s.isEmpty() || s.length() > 9) return Optional.empty();
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) < '0' || s.charAt(i) > '9') return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(Long.parseLong(s)));
        });
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
