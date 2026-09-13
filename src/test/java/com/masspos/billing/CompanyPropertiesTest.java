package com.masspos.billing;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code pos.company} only seeds the shop's settings row on the very first start; after that the
 * settings screen owns them. What still matters here is that a typo in a seeded value is caught
 * before it can reach an invoice.
 */
class CompanyPropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final ApplicationContextRunner binding = new ApplicationContextRunner()
            .withUserConfiguration(BindCompany.class);

    @Test
    void anUnconfiguredInstallIsValid() {
        assertThat(VALIDATOR.validate(company(null, null, null, List.of(), null, null, null, null))).isEmpty();
    }

    @Test
    void anythingThatIsSetMustBeValid() {
        CompanyProperties typos = company("Mass Retail Private Limited", "MASS MART", "27AAPFU0939F1ZW",
                List.of("12 MG Road"), "not-an-email", "U52100MH2020PTC12345", "1152199900012", "no-at-sign");

        assertThat(VALIDATOR.validate(typos))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("gstin", "email", "cin", "fssaiLicense", "upiVpa");
    }

    @Test
    void normalisesCaseAndWhitespace() {
        CompanyProperties sloppy = company("  Mass Retail Private Limited ", "", " 27aapfu0939f1zv ",
                List.of(" 12 MG Road ", " "), null, "u52100mh2020ptc123456", null, null);

        assertThat(sloppy.gstin()).isEqualTo("27AAPFU0939F1ZV");
        assertThat(sloppy.cin()).isEqualTo("U52100MH2020PTC123456");
        assertThat(sloppy.address()).containsExactly("12 MG Road");
        assertThat(sloppy.displayName()).isEqualTo("Mass Retail Private Limited");
        assertThat(VALIDATOR.validate(sloppy)).isEmpty();
    }

    @Test
    void bindsFromPropertiesIncludingIndexedAddressLines() {
        binding.withPropertyValues(
                        "pos.company.legal-name=Mass Retail Private Limited",
                        "pos.company.gstin=27AAPFU0939F1ZV",
                        "pos.company.address[0]=12 MG Road, Camp",
                        "pos.company.address[1]=Pune 411001")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CompanyProperties.class).address())
                            .containsExactly("12 MG Road, Camp", "Pune 411001");
                });
    }

    @Test
    void mistypedGstinStopsTheApplicationFromStarting() {
        binding.withPropertyValues("pos.company.gstin=27AAPFU0939F1ZW")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("not a valid GSTIN"));
    }

    private static CompanyProperties company(String legalName, String tradeName, String gstin, List<String> address,
                                             String email, String cin, String fssaiLicense, String upiVpa) {
        return new CompanyProperties(legalName, tradeName, gstin, address, null, email, null, cin, fssaiLicense, upiVpa);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CompanyProperties.class)
    static class BindCompany {
    }
}
