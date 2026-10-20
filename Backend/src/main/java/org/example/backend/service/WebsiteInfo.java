package org.example.backend.service;

import org.example.backend.exception.ApiException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a brand's name and logo from its website — decided 2026-10-20, so a
 * brand is added by typing its website alone.
 *
 * <p>The name is the site's own (its og:site_name, else its title); the logo is
 * the biggest icon the page declares, else /favicon.ico. A site that cannot be
 * reached still gives a brand — named after its domain, with no logo.
 *
 * <p>⚠️ This makes the server fetch an address somebody typed, so it refuses
 * anything that is not a public website: no IP addresses, nothing that
 * resolves to this machine or a private network.
 */
@Service
public class WebsiteInfo {

    private static final Logger log = LoggerFactory.getLogger(WebsiteInfo.class);

    private static final Pattern DOMAIN = Pattern.compile("^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final int MAX_BYTES = 1_000_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Info(String domain, String name, String logoUrl) {
    }

    /** "https://www.Ask4Loan.ca/contact" → "ask4loan.ca". Refuses anything that is not a domain. */
    public static String domainOf(String input) {
        String value = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
        value = value.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int cut = value.indexOf('/');
        if (cut >= 0) value = value.substring(0, cut);
        cut = value.indexOf(':');
        if (cut >= 0) value = value.substring(0, cut);
        if (value.startsWith("www.")) value = value.substring(4);
        if (!DOMAIN.matcher(value).matches()) {
            throw ApiException.badRequest("Type the brand's website, like ask4loan.ca.");
        }
        return value;
    }

    public Info read(String input) {
        String domain = domainOf(input);
        refusePrivate(domain);

        String fallbackName = nameFromDomain(domain);
        try {
            Document page = fetch("https://" + domain);
            if (page == null) page = fetch("https://www." + domain);
            if (page == null) return new Info(domain, fallbackName, null);
            return new Info(domain, nameOf(page).orElse(fallbackName), logoOf(page, domain));
        } catch (RuntimeException e) {
            log.warn("Could not read {}: {}", domain, e.toString());
            return new Info(domain, fallbackName, null);
        }
    }

    private Document fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (compatible; NordenCapitalLMS/1.0)")
                    .header("Accept", "text/html")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // A redirect can lead anywhere; the place it ended up must be public too.
            refusePrivate(response.uri().getHost());
            if (response.statusCode() / 100 != 2) return null;
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_BYTES);
            }
            return Jsoup.parse(new String(body, StandardCharsets.UTF_8), response.uri().toString());
        } catch (IOException | IllegalArgumentException | ApiException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Optional<String> nameOf(Document page) {
        String site = page.select("meta[property=og:site_name]").attr("content").strip();
        if (!site.isEmpty()) return Optional.of(shorten(site));
        String title = page.title().strip();
        if (title.isEmpty()) return Optional.empty();
        // "Ask4Loan | Fast personal loans" → "Ask4Loan".
        String first = title.split("\\s+[|\\-–—:·]\\s+")[0].strip();
        return Optional.of(shorten(first.isEmpty() ? title : first));
    }

    /** The biggest declared icon, else the page's share image, else /favicon.ico. */
    private static String logoOf(Document page, String domain) {
        // Not Safari's "mask-icon": a one-colour outline meant to be tinted, not a logo.
        Optional<Element> icon = page.select("link[rel~=(?i)(apple-touch-icon|icon)][href]").stream()
                .filter(link -> !link.attr("rel").toLowerCase(Locale.ROOT).contains("mask"))
                .max(Comparator.comparingInt(WebsiteInfo::iconSize));
        if (icon.isPresent()) return icon.get().absUrl("href");
        String og = page.select("meta[property=og:image]").attr("abs:content");
        if (!og.isBlank()) return og;
        return "https://" + domain + "/favicon.ico";
    }

    private static int iconSize(Element link) {
        int best = link.attr("rel").toLowerCase(Locale.ROOT).contains("apple-touch-icon") ? 180 : 16;
        for (String size : link.attr("sizes").split("\\s+")) {
            String[] wh = size.toLowerCase(Locale.ROOT).split("x");
            try {
                if (wh.length == 2) best = Math.max(best, Integer.parseInt(wh[0]));
            } catch (NumberFormatException ignored) {
                // "any" and the like.
            }
        }
        if (link.attr("href").toLowerCase(Locale.ROOT).endsWith(".svg")) best = Math.max(best, 512);
        return best;
    }

    /** "ask4loan.ca" → "Ask4loan". */
    private static String nameFromDomain(String domain) {
        String first = domain.split("\\.")[0];
        return first.isEmpty() ? domain : Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    private static String shorten(String value) {
        return value.length() <= 80 ? value : value.substring(0, 80).strip();
    }

    private static void refusePrivate(String host) {
        if (host == null) throw ApiException.badRequest("That website cannot be read.");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()
                        || isUniqueLocal(address)) {
                    throw ApiException.badRequest("That is not a public website.");
                }
            }
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("No website was found at " + host + ".");
        }
    }

    /** IPv6 fc00::/7 — the private range Java does not flag. */
    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
