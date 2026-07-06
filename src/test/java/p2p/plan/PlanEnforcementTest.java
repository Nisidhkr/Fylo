package p2p.plan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p2p.auth.AuthContext;
import p2p.engine.TransferEngine;
import p2p.security.FileSafetyService;
import p2p.service.FileSharer;
import p2p.share.LinkShareService;
import p2p.share.ShareLink;
import p2p.storage.LocalStorageProvider;
import p2p.transfer.TransferManager;
import p2p.user.TransferHistoryService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free vs Premium enforcement at the service layer (Link Share is the
 * gatekeeper for storage-backed entitlements).
 */
class PlanEnforcementTest {

    @TempDir
    Path dataDir;

    private TransferEngine engine;
    private PlanService plans;
    private LinkShareService links;
    private final AuthContext user = new AuthContext("user-1", "nisidh");

    @BeforeEach
    void setUp() throws IOException {
        engine = new TransferEngine(new FileSharer(), new TransferManager(1),
                new LocalStorageProvider(dataDir));
        plans = new PlanService(dataDir);
        links = new LinkShareService(engine, new TransferHistoryService(), plans,
                new FileSafetyService(true), dataDir);
    }

    @AfterEach
    void tearDown() {
        links.close();
        engine.close();
    }

    private ShareLink create(LinkShareService.LinkOptions options) throws IOException {
        byte[] bytes = "hello fylo".getBytes(StandardCharsets.UTF_8);
        return links.create(user, new ByteArrayInputStream(bytes), "notes.txt",
                bytes.length, options);
    }

    @Test
    void freeDefaultsToTwoDayExpiry() throws IOException {
        ShareLink link = create(LinkShareService.LinkOptions.DEFAULTS);
        long ttl = link.expiresAtEpochMs() - link.createdAtEpochMs();
        long twoDays = Duration.ofDays(2).toMillis();
        assertTrue(Math.abs(ttl - twoDays) < 60_000, "free links expire in ~2 days");
    }

    @Test
    void freeCannotUsePremiumFeatures() {
        assertEquals("password_links", assertThrows(PlanLimitException.class, () ->
                create(new LinkShareService.LinkOptions(null, "secret", 0))).limit());
        assertEquals("download_limits", assertThrows(PlanLimitException.class, () ->
                create(new LinkShareService.LinkOptions(null, null, 5))).limit());
        assertEquals("link_expiry", assertThrows(PlanLimitException.class, () ->
                create(new LinkShareService.LinkOptions(Duration.ofDays(30), null, 0))).limit());
        assertEquals("link_revocation", assertThrows(PlanLimitException.class, () ->
                links.revoke(user, "whatever")).limit());
    }

    @Test
    void premiumUnlocksPasswordDownloadLimitsAndRevocation() throws IOException {
        plans.grantPremium(user.userId(), 500L << 30, 0);

        ShareLink link = create(new LinkShareService.LinkOptions(
                Duration.ofDays(30), "secret", 2));
        assertTrue(link.passwordProtected());
        assertEquals(2, link.maxDownloads());
        assertTrue(links.passwordMatches(link, "secret"));
        assertFalse(links.passwordMatches(link, "wrong"));

        // Download limit: after 2 downloads the link stops resolving.
        links.recordDownload(link.slug());
        links.recordDownload(link.slug());
        assertTrue(links.resolve(link.slug()).isEmpty(), "exhausted link must not resolve");

        // Revocation kills the link but keeps the record.
        ShareLink second = create(new LinkShareService.LinkOptions(null, null, 0));
        assertTrue(links.revoke(user, second.slug()));
        assertTrue(links.resolve(second.slug()).isEmpty(), "revoked link must not resolve");
        assertTrue(links.listFor(user).stream()
                .anyMatch(l -> l.slug().equals(second.slug()) && l.revoked()));
    }

    @Test
    void premiumNeverExpire() throws IOException {
        plans.grantPremium(user.userId(), 500L << 30, 0);
        ShareLink link = create(new LinkShareService.LinkOptions(Duration.ZERO, null, 0));
        assertEquals(Long.MAX_VALUE, link.expiresAtEpochMs());
    }

    @Test
    void executablesBlockedOnPublicLinks() {
        byte[] bytes = new byte[]{0x4D, 0x5A};
        PlanLimitException blocked = assertThrows(PlanLimitException.class, () ->
                links.create(user, new ByteArrayInputStream(bytes), "invoice.pdf.exe",
                        bytes.length, LinkShareService.LinkOptions.DEFAULTS));
        assertEquals("file_blocked", blocked.limit());
    }

    @Test
    void expiredSubscriptionFallsBackToFree() {
        plans.grantPremium(user.userId(), 500L << 30, System.currentTimeMillis() - 1000);
        assertEquals("FREE", plans.entitlementsFor(user.userId()).tierName());
    }
}
