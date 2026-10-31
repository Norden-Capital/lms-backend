package org.example.backend.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the apply form sends, with LeadScout's field names. Every answer is
 * checked again here: the browser's checks can be skipped by anyone.
 */
public final class ApplyDtos {

    private static final Set<Integer> TIME_AT_ADDRESS = Set.of(6, 12, 18, 24, 25, 60);
    private static final Set<Integer> EMPLOYED_MONTHS = Set.of(2, 6, 12, 18, 24, 25, 60);
    /** LeadScout asks for job details from these income types only. */
    private static final Set<String> WORKING = Set.of("full_time", "part_time", "self_employed", "gig_worker");

    private ApplyDtos() {
    }

    /**
     * Step 1. {@code stepToken} is there when the person came back with Previous
     * and pressed Next again: the same application is updated.
     */
    public record StepOne(
            @Size(max = 64) String stepToken,
            @NotBlank @Size(max = 255) String host,
            @Pattern(regexp = "en|fr", message = "Choose English or French.") @NotNull String language,
            @Size(max = 400) String userAgent,
            Map<String, String> extra,

            @NotNull @Min(value = 300, message = "Choose an amount from $300 to $3,000.")
            @Max(value = 3000, message = "Choose an amount from $300 to $3,000.") Integer requestedAmount,
            @NotBlank(message = "Enter your first name.") @Size(max = 80) String firstName,
            @NotBlank(message = "Enter your last name.") @Size(max = 80) String lastName,
            @NotBlank(message = "Enter a valid email.") @Email(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Enter a valid email.") @Size(max = 254) String email,
            @NotNull(message = "Enter a valid phone number.")
            @Pattern(regexp = "\\(\\d{3}\\) \\d{3}-\\d{4}", message = "Enter a valid phone number.") String phone,
            @NotNull(message = "You must be 18 or older.") LocalDate birthDate,
            @NotBlank(message = "Enter your address.") @Size(max = 200) String address,
            @NotBlank(message = "Enter your city.") @Size(max = 100) String city,
            @NotNull(message = "Choose one.")
            @Pattern(regexp = "AB|BC|MB|NB|NL|NS|ON|PE|QC|SK|NT|NU|YT", message = "Choose one.") String province,
            @NotNull(message = "Enter a valid postal code.")
            @Pattern(regexp = "[ABCEGHJ-NPRSTVXY]\\d[A-Z]-\\d[A-Z]\\d", message = "Enter a valid postal code.") String postalCode,
            @NotNull @AssertTrue(message = "You must agree to continue.") Boolean consent,
            @NotNull @AssertTrue(message = "You must accept to continue.") Boolean terms,
            boolean optIn,
            boolean optInPhone
    ) {
        @AssertTrue(message = "You must be 18 or older.")
        public boolean isAdult() {
            if (birthDate == null) return true; // reported by @NotNull
            LocalDate today = LocalDate.now();
            return !birthDate.isAfter(today.minusYears(18)) && birthDate.isAfter(today.minusYears(100));
        }

        /** The answers under the names LeadScout uses. */
        public Map<String, Object> answers() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestedAmount", requestedAmount);
            data.put("email", email.trim());
            data.put("firstName", firstName.trim());
            data.put("lastName", lastName.trim());
            data.put("phone", phone);
            data.put("birthDate", birthDate.toString());
            data.put("address", address.trim());
            data.put("postalCode", postalCode);
            data.put("city", city.trim());
            data.put("province", province);
            data.put("country", "CA");
            data.put("optIn", optIn);
            data.put("optInPhone", optInPhone);
            return data;
        }
    }

    /** Step 2. */
    public record StepTwo(
            @NotNull(message = "Choose one.")
            @Pattern(regexp = "Debt Consolidation|Credit Card Consolidation|Home Improvement|Home Buying|Major Purchase"
                    + "|Vacation|Moving or Relocation|Medical Expenses|Emergency Expense|Other", message = "Choose one.") String purpose,
            @NotNull(message = "Choose one.")
            @Pattern(regexp = "good|fair|low|unknown", message = "Choose one.") String creditScore,
            @NotNull(message = "Choose one.")
            @Pattern(regexp = "full_time|part_time|self_employed|gig_worker|retired|disability|social_security|unemployed",
                    message = "Choose one.") String incomeType,
            @NotNull(message = "Choose one.") @Pattern(regexp = "yes|no", message = "Choose one.") String directDeposit,
            @NotNull(message = "Enter your monthly income.") @Min(0) @Max(10_000_000) Integer monthlyIncome,
            @NotNull(message = "Choose one.") Integer timeAtAddress,
            @NotNull(message = "Choose one.") @Pattern(regexp = "yes|no", message = "Choose one.") String ownHome,
            @NotNull(message = "Enter an amount (0 if none).") @Min(0) @Max(10_000_000) Integer rentMortgagePayment,
            @Min(0) @Max(1_000_000_000) Integer propertyValue,
            @Min(0) @Max(1_000_000_000) Integer propertyMortgage,
            @NotNull(message = "Choose one.")
            @Pattern(regexp = "inBankruptcy|inDMP|inConsumerProposal|none", message = "Choose one.") String financialSituation,
            @NotNull(message = "Enter an amount (0 if none).") @Min(0) @Max(100_000_000) Integer unsecuredDebtAmount,
            @Size(max = 120) String jobTitle,
            @Size(max = 120) String employerName,
            Integer employedMonths,
            @Pattern(regexp = "WEEKLY|BIWEEKLY|TWICEMONTHLY|MONTHLY", message = "Choose one.") String payFrequency,
            LocalDate payDate1
    ) {
        private boolean working() {
            return WORKING.contains(incomeType);
        }

        private boolean owner() {
            return "yes".equals(ownHome);
        }

        @AssertTrue(message = "Choose one.")
        public boolean isTimeAtAddressAllowed() {
            return timeAtAddress == null || TIME_AT_ADDRESS.contains(timeAtAddress);
        }

        @AssertTrue(message = "Enter the home value and the mortgage left.")
        public boolean isHomeComplete() {
            return !owner() || (propertyValue != null && propertyMortgage != null);
        }

        @AssertTrue(message = "Fill in your job details.")
        public boolean isJobComplete() {
            if (!working()) return true;
            return jobTitle != null && !jobTitle.isBlank()
                    && employerName != null && !employerName.isBlank()
                    && employedMonths != null && EMPLOYED_MONTHS.contains(employedMonths)
                    && payFrequency != null
                    && payDate1 != null;
        }

        /** The answers under the names LeadScout uses; what does not apply to the person is left out. */
        public Map<String, Object> answers() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("purpose", purpose);
            data.put("creditScore", creditScore);
            data.put("incomeType", incomeType);
            data.put("directDeposit", directDeposit);
            data.put("timeAtAddress", timeAtAddress);
            data.put("ownHome", ownHome);
            data.put("rentMortgagePayment", rentMortgagePayment);
            if (owner()) {
                data.put("propertyValue", propertyValue);
                data.put("propertyMortgage", propertyMortgage);
            }
            data.put("financialSituation", financialSituation);
            data.put("monthlyIncome", monthlyIncome);
            if (working()) {
                data.put("jobTitle", jobTitle.trim());
                data.put("employerName", employerName.trim());
                data.put("employedMonths", employedMonths);
                data.put("payFrequency", payFrequency);
                data.put("payDate1", payDate1.toString());
            }
            data.put("unsecuredDebtAmount", unsecuredDebtAmount);
            return data;
        }
    }

    public record StepOneResult(String stepToken) {
    }

    /**
     * What the form shows next: GO_TO_LINK sends the person to {@code redirectUrl},
     * ALREADY_APPLIED and TRY_LATER are the form's own screens.
     */
    public record StepTwoResult(String outcome, String redirectUrl) {
        public static final String GO_TO_LINK = "GO_TO_LINK";
        public static final String ALREADY_APPLIED = "ALREADY_APPLIED";
        public static final String TRY_LATER = "TRY_LATER";
    }
}
