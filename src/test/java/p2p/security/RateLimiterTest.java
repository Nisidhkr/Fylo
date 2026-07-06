package p2p.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.AUTH),
                    "request " + i + " should pass");
        }
        assertFalse(limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.AUTH),
                "11th auth request within a minute must be rejected");
    }

    @Test
    void clientsAndPoliciesAreIsolated() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.AUTH);
        }
        // Different client: fresh bucket.
        assertTrue(limiter.tryAcquire("5.6.7.8", RateLimiter.Policy.AUTH));
        // Same client, different policy: fresh bucket.
        assertTrue(limiter.tryAcquire("1.2.3.4", RateLimiter.Policy.API));
    }
}
