package com.masspos.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the vendor's tools/license/LicenseTool.java as the vendor would, and checks the app accepts what
 * it issues. The tool is a separate single-file program, so this is what keeps the two formats in step.
 */
class LicenseToolCompatibilityTest {

    private static final Path TOOL = Path.of("tools", "license", "LicenseTool.java");

    @Test
    void theAppReadsLicencesTheVendorToolIssues(@TempDir Path vendorDir) throws Exception {
        LocalDate validUntil = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusMonths(12);

        run(vendorDir, "keygen", "--dir", vendorDir.toString());
        String issued = run(vendorDir, "issue", "--dir", vendorDir.toString(), "--machine", "abcd-efgh-jkmn-pqrs",
                // No double quotes: Windows drops them from a child process's arguments before the tool sees them.
                "--customer-id", "C042", "--customer", "Anand's Fresh Mart, Pune",
                "--valid-until", validUntil.toString(), "--grace-days", "10", "--warn-days", "7");

        String key = issued.lines().filter(line -> line.startsWith("MPL1.")).findFirst().orElseThrow();
        VendorKey vendor = VendorKey.fromBase64(Files.readString(vendorDir.resolve("vendor-public.key")));
        LicensePayload payload = LicenseToken.read(key, vendor.publicKey());

        assertThat(payload.machineId()).isEqualTo("ABCD-EFGH-JKMN-PQRS");
        assertThat(payload.customerId()).isEqualTo("C042");
        assertThat(payload.customer()).isEqualTo("Anand's Fresh Mart, Pune");
        assertThat(payload.plan()).isEqualTo("SUBSCRIPTION");
        assertThat(payload.validUntil()).isEqualTo(validUntil);
        assertThat(payload.graceDays()).isEqualTo(10);
        assertThat(payload.warnDays()).isEqualTo(7);

        try (var files = Files.list(vendorDir.resolve("issued"))) {
            assertThat(Files.readString(files.findFirst().orElseThrow()).strip()).isEqualTo(key);
        }
        assertThat(run(vendorDir, "list", "--dir", vendorDir.toString()))
                .contains("Anand's Fresh Mart, Pune", "C042", "ABCD-EFGH-JKMN-PQRS", validUntil.toString());
        assertThat(run(vendorDir, "inspect", "--dir", vendorDir.toString(), key))
                .contains("Signature: valid");
    }

    private static String run(Path workDir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add(TOOL.toAbsolutePath().toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
