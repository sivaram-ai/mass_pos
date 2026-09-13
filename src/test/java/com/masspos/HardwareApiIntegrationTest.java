package com.masspos;

import com.masspos.auth.AuthService;
import com.masspos.billing.Invoice;
import com.masspos.billing.InvoiceSequence;
import com.masspos.catalog.Product;
import com.masspos.user.User;
import com.masspos.user.UserRole;
import com.masspos.web.LocalApiGuard;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static com.masspos.TestFixtures.newCashier;
import static com.masspos.TestFixtures.newInvoice;
import static com.masspos.TestFixtures.newProduct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"pos.data-dir=" + HardwareApiIntegrationTest.DATA_DIR, "pos.printer.type=FILE"})
@AutoConfigureMockMvc
class HardwareApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/hardware-it";
    private static final Path PRINTER_OUT = Path.of(DATA_DIR, "printer-out");

    private static String cashierToken;
    private static String managerToken;

    static {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
        try {
            Files.createDirectories(Path.of(DATA_DIR));
            // The per-till settings file, loaded through spring.config.import.
            Files.writeString(Path.of(DATA_DIR, "pos.properties"), """
                    pos.company.trade-name=Imported Mart
                    pos.company.phone=020-1112223
                    pos.receipt.footer[0]=Imported footer line
                    """);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    EntityManager em;
    @Autowired
    AuthService auth;

    @BeforeEach
    void staffOnShift() {
        if (cashierToken == null) {
            cashierToken = TestAccounts.tokenFor(auth, "cashier-it", UserRole.CASHIER);
            managerToken = TestAccounts.tokenFor(auth, "manager-it", UserRole.MANAGER);
        }
    }

    @Test
    void rejectsForeignHostNames() throws Exception {
        mvc.perform(as(get("/api/hardware/printer/status"), cashierToken)
                        .header(HttpHeaders.HOST, "evil.example:8765"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsStateChangesWithoutTheClientHeader() throws Exception {
        mvc.perform(post("/api/hardware/drawer/open").header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"change\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesApiCallsWithoutASignIn() throws Exception {
        mvc.perform(get("/api/hardware/printer/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/hardware/printer/status").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cashierCannotOpenThePrinterSetup() throws Exception {
        mvc.perform(as(get("/api/hardware/serial-ports"), cashierToken)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/hardware/serial-ports"), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void opensTheDrawerThroughThePrinter() throws Exception {
        mvc.perform(as(post("/api/hardware/drawer/open"), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"Change for Rs 500\"}"))
                .andExpect(status().isNoContent());

        assertThat(Files.readAllBytes(latestJob(".bin")))
                .containsSequence((byte) 0x1B, (byte) 'p', (byte) 0, (byte) 50, (byte) 250);
    }

    @Test
    void drawerOpeningNeedsAReason() throws Exception {
        mvc.perform(as(post("/api/hardware/drawer/open"), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filePrinterReportsNotResponding() throws Exception {
        mvc.perform(as(get("/api/hardware/printer/status"), cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responding").value(false));
    }

    @Test
    void testPageShowsTheConfiguredShopAndTill() throws Exception {
        mvc.perform(as(post("/api/hardware/printer/test-page"), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test"))
                .andExpect(status().isNoContent());

        assertThat(Files.readString(latestJob(".txt")))
                .contains("TEST PAGE", "Imported Mart", "Counter 1 (T1)", "Rupee sign prints as: Rs.");
    }

    @Test
    void receiptUsesTheInvoiceSnapshotPlusTheTillsCurrentContactAndFooter() throws Exception {
        UUID invoiceId = tx.execute(s -> {
            User cashier = newCashier();
            Product product = newProduct(10_000);
            InvoiceSequence sequence = new InvoiceSequence("T1", "2627");
            em.persist(cashier);
            em.persist(product);
            em.persist(sequence);
            Invoice invoice = newInvoice(sequence.next(), cashier, product);
            em.persist(invoice);
            return invoice.getId();
        });

        mvc.perform(as(post("/api/invoices/{id}/receipt", invoiceId), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("{\"copy\":\"DUPLICATE\"}"))
                .andExpect(status().isNoContent());

        assertThat(Files.readString(latestJob(".txt")))
                .contains("MASS MART", "GSTIN: 27AAPFU0939F1ZV", "CIN: U52100MH2020PTC123456", "DUPLICATE COPY",
                        "Invoice: T1-2627-00001", "TOTAL Rs.", "Ph: 020-1112223", "Imported footer line")
                .doesNotContain("Imported Mart", "Visit again");
    }

    @Test
    void reprintsNeverOpenTheDrawer() throws Exception {
        mvc.perform(as(post("/api/invoices/{id}/receipt", UUID.randomUUID()), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("{\"copy\":\"DUPLICATE\",\"openDrawer\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownInvoiceIsNotFound() throws Exception {
        mvc.perform(as(post("/api/invoices/{id}/receipt", UUID.randomUUID()), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("{\"copy\":\"ORIGINAL\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void existingSettingsFileIsNeverOverwritten() throws IOException {
        assertThat(Files.readString(Path.of(DATA_DIR, "pos.properties")))
                .startsWith("pos.company.trade-name=Imported Mart");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static Path latestJob(String suffix) throws IOException {
        try (Stream<Path> files = Files.list(PRINTER_OUT)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }
    }
}
