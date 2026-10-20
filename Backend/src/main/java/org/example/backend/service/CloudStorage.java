package org.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * The off-site copy of each backup, in a Cloudflare R2 bucket.
 *
 * <p>R2 speaks the Amazon S3 protocol. Only three calls are needed — put, get
 * and delete one file — so they are signed here by hand (AWS Signature V4)
 * rather than pulling in the whole AWS library for them. Any S3-compatible
 * store works the same way, which is how it is tested locally against MinIO.
 *
 * <p>Unset means no off-site copy: backups are then kept on the server only,
 * and the Backups page says "Server only" so the gap is visible.
 */
@Service
public class CloudStorage {

    private static final Logger log = LoggerFactory.getLogger(CloudStorage.class);

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String REGION = "auto";

    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public CloudStorage(@Value("${app.backup.r2.endpoint:}") String endpoint,
                        @Value("${app.backup.r2.bucket:}") String bucket,
                        @Value("${app.backup.r2.access-key:}") String accessKey,
                        @Value("${app.backup.r2.secret-key:}") String secretKey) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        if (!isConfigured()) {
            log.warn("No Cloudflare R2 bucket configured — backups are kept on the server only.");
        }
    }

    public boolean isConfigured() {
        return !endpoint.isBlank() && !bucket.isBlank() && !accessKey.isBlank() && !secretKey.isBlank();
    }

    /** Uploads one file. {@code sha256} is its hash, which R2 checks on arrival. */
    public void put(String name, Path file, String sha256) throws IOException {
        HttpRequest request = signed("PUT", name, sha256)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .timeout(Duration.ofMinutes(30))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Cloudflare refused the upload (" + response.statusCode() + ")" + reason(response.body()));
        }
    }

    /** Opens one file for reading. The caller closes the stream. */
    public InputStream get(String name) throws IOException {
        HttpRequest request = signed("GET", name, EMPTY_SHA256).GET().timeout(Duration.ofMinutes(30)).build();
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Cloudflare refused the download (" + response.statusCode() + ")" + reason(body));
        }
        return response.body();
    }

    /** Deletes one file. Deleting a file that is already gone is not an error. */
    public void delete(String name) throws IOException {
        HttpRequest request = signed("DELETE", name, EMPTY_SHA256).DELETE().timeout(Duration.ofMinutes(1)).build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2 && response.statusCode() != 404) {
            throw new IOException("Cloudflare refused the delete (" + response.statusCode() + ")" + reason(response.body()));
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while talking to Cloudflare", e);
        }
    }

    /** The S3 error code, e.g. " — SignatureDoesNotMatch", which says what to fix. */
    private static String reason(String body) {
        if (body == null) return "";
        int start = body.indexOf("<Code>");
        int end = body.indexOf("</Code>");
        return start >= 0 && end > start ? " — " + body.substring(start + 6, end) : "";
    }

    // ── AWS Signature V4 ────────────────────────────────────────────────────

    private HttpRequest.Builder signed(String method, String name, String payloadSha256) {
        if (!isConfigured()) throw new IllegalStateException("Cloud storage is not configured");

        URI uri = URI.create(endpoint + "/" + encode(bucket) + "/" + encodePath(name));
        String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();

        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
        String day = amzDate.substring(0, 8);
        String scope = day + "/" + REGION + "/s3/aws4_request";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";

        String canonicalRequest = method + "\n"
                + uri.getRawPath() + "\n"
                + "\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadSha256 + "\n"
                + "x-amz-date:" + amzDate + "\n"
                + "\n"
                + signedHeaders + "\n"
                + payloadSha256;

        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);

        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, REGION);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));

        return HttpRequest.newBuilder(uri)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadSha256)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
    }

    /** Each path segment encoded, the slashes between them kept. */
    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!out.isEmpty()) out.append('/');
            out.append(encode(segment));
        }
        return out.toString();
    }

    /** RFC 3986, which is what S3 signs: spaces as %20, and ~ left alone. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
