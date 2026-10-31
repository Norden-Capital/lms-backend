package org.example.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the apply tests share: one real PostgreSQL, and answers that pass every check. */
final class ApplyTestSupport {

    static final String SECRET = "test-proxy-secret";

    private static EmbeddedPostgres postgres;

    private ApplyTestSupport() {
    }

    static synchronized void database(DynamicPropertyRegistry registry) {
        try {
            if (postgres == null) postgres = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.proxy-secret", () -> SECRET);
        registry.add("app.leadscout.retry-delay-ms", () -> "0");
    }

    static Map<String, Object> stepOne(String host, String email) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("host", host);
        form.put("language", "en");
        form.put("userAgent", "Test Browser");
        form.put("extra", Map.of("aff_utm_source", "google", "not_allowed", "dropped"));
        form.put("requestedAmount", 1500);
        form.put("firstName", "Secretname");
        form.put("lastName", "Tester");
        form.put("email", email);
        form.put("phone", "(514) 555-1234");
        form.put("birthDate", "1990-03-15");
        form.put("address", "866 rue Elgar");
        form.put("city", "Montreal");
        form.put("province", "QC");
        form.put("postalCode", "H3E-1C2");
        form.put("consent", true);
        form.put("terms", true);
        form.put("optIn", false);
        form.put("optInPhone", false);
        return form;
    }

    static Map<String, Object> stepTwo() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("purpose", "Emergency Expense");
        form.put("creditScore", "good");
        form.put("incomeType", "retired");
        form.put("directDeposit", "yes");
        form.put("monthlyIncome", 2200);
        form.put("timeAtAddress", 60);
        form.put("ownHome", "no");
        form.put("rentMortgagePayment", 900);
        form.put("financialSituation", "none");
        form.put("unsecuredDebtAmount", 0);
        return form;
    }
}
