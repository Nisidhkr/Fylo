package p2p.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    @TempDir
    Path dataDir;

    @Test
    void issueAndVerifyRoundTrip() throws IOException {
        JwtService jwt = new JwtService(dataDir);
        String token = jwt.issue("user-1", "nisidh", JwtService.TokenType.ACCESS,
                Duration.ofMinutes(5));
        Optional<JwtService.Claims> claims = jwt.verify(token, JwtService.TokenType.ACCESS);
        assertTrue(claims.isPresent());
        assertEquals("user-1", claims.get().subject());
        assertEquals("nisidh", claims.get().username());
    }

    @Test
    void rejectsWrongType_expired_and_tampered() throws IOException {
        JwtService jwt = new JwtService(dataDir);
        String access = jwt.issue("u", "n", JwtService.TokenType.ACCESS, Duration.ofMinutes(5));
        assertTrue(jwt.verify(access, JwtService.TokenType.REFRESH).isEmpty(),
                "access token must not verify as refresh");

        String expired = jwt.issue("u", "n", JwtService.TokenType.ACCESS,
                Duration.ofSeconds(-10));
        assertTrue(jwt.verify(expired, JwtService.TokenType.ACCESS).isEmpty(),
                "expired token must be rejected");

        String tampered = access.substring(0, access.length() - 4) + "AAAA";
        assertTrue(jwt.verify(tampered, JwtService.TokenType.ACCESS).isEmpty(),
                "bad signature must be rejected");
    }

    @Test
    void secretPersistsAcrossRestarts() throws IOException {
        String token = new JwtService(dataDir)
                .issue("u", "n", JwtService.TokenType.ACCESS, Duration.ofMinutes(5));
        JwtService restarted = new JwtService(dataDir);
        assertTrue(restarted.verify(token, JwtService.TokenType.ACCESS).isPresent(),
                "token must survive a service restart (persisted secret)");
    }
}
