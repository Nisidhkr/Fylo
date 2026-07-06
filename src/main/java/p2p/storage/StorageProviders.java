package p2p.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Provider selection — the ONE place deployment chooses where bytes live.
 *
 * <pre>
 * FYLO_STORAGE=local   (default)  → LocalStorageProvider under dataDir
 * FYLO_STORAGE=minio               → MinioStorageProvider
 *     S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY
 * FYLO_STORAGE=s3                  → S3StorageProvider
 *     S3_REGION, S3_BUCKET [, S3_ACCESS_KEY, S3_SECRET_KEY]
 * </pre>
 *
 * Migration between providers is data-only: copy objects, keep ids (they are
 * the object keys), flip the env var. No code changes anywhere else.
 */
public final class StorageProviders {

    private StorageProviders() {
    }

    public static StorageProvider fromEnv(Path dataDir) throws IOException {
        String kind = System.getenv().getOrDefault("FYLO_STORAGE", "local")
                .toLowerCase(java.util.Locale.ROOT);
        Path spool = dataDir.resolve("spool");
        return switch (kind) {
            case "local" -> new LocalStorageProvider(dataDir);
            case "minio" -> new MinioStorageProvider(
                    required("S3_ENDPOINT"), required("S3_BUCKET"),
                    required("S3_ACCESS_KEY"), required("S3_SECRET_KEY"), spool);
            case "s3" -> new S3StorageProvider(
                    required("S3_REGION"), required("S3_BUCKET"),
                    System.getenv("S3_ACCESS_KEY"), System.getenv("S3_SECRET_KEY"), spool);
            default -> throw new IOException("Unknown FYLO_STORAGE: " + kind);
        };
    }

    private static String required(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IOException(name + " must be set for this storage provider");
        }
        return value;
    }
}
