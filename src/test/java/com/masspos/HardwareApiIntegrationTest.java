package com.masspos;

import com.masspos.auth.AuthService;
import com.masspos.billing.Invoice;
import com.masspos.billing.InvoiceSequence;
import com.masspos.catalog.Product;
import com.masspos.user.User;
import com.masspos.user.UserRole;
import com.masspos.web.LocalApiGuard;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                        "Invoice: 00001", "Counter: T1", "TOTAL Rs.", "Ph: 020-1112223", "Imported footer line")
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
    void thePrinterPageShowsTheSetupAndWhatThisMachineHasToOfferIt() throws Exception {
        mvc.perform(as(get("/api/hardware/printer/settings"), cashierToken)).andExpect(status().isForbidden());

        mvc.perform(as(get("/api/hardware/printer/settings"), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printer.type").value("FILE"))
                .andExpect(jsonPath("$.printer.columns").value(48))
                .andExpect(jsonPath("$.target").value(startsWith("FILE ")))
                .andExpect(jsonPath("$.serialPorts").isArray())
                .andExpect(jsonPath("$.installedPrinters").isArray());
    }

    /** The point of the screen: no pos.properties, no restart. */
    @Test
    void aPaperWidthSavedOnScreenAppliesToTheVeryNextJob() throws Exception {
        savePrinter(printerJson("FILE", 32, ""));

        printTestPage().andExpect(status().isNoContent());

        assertThat(Files.readString(latestJob(".txt")))
                .contains("12345678901234567890123456789012")
                .doesNotContain("123456789012345678901234567890123");
    }

    @Test
    void aPrinterThatIsNotThereFailsTheJobAndTheTillCarriesOn() throws Exception {
        savePrinter(printerJson("WINDOWS", 48, "No Such Printer 9000"));

        printTestPage()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("No Such Printer 9000")));

        savePrinter(printerJson("FILE", 48, ""));
        printTestPage().andExpect(status().isNoContent());
    }

    @Test
    void refusesPaperTooNarrowToLayABillOutOn() throws Exception {
        mvc.perform(as(put("/api/hardware/printer/settings"), managerToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(printerJson("FILE", 10, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.columns").value("must be at least 24"))
                .andExpect(jsonPath("$.detail").value(containsString("Characters per line must be at least 24")));
    }

    @Test
    void onlyAManagerChangesThePrinter() throws Exception {
        mvc.perform(as(put("/api/hardware/printer/settings"), cashierToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(printerJson("FILE", 48, "")))
                .andExpect(status().isForbidden());
    }

    @Test
    void existingSettingsFileIsNeverOverwritten() throws IOException {
        assertThat(Files.readString(Path.of(DATA_DIR, "pos.properties")))
                .startsWith("pos.company.trade-name=Imported Mart");
    }

    /** Back to the file printer, so one test's choice of printer is not the next one's. */
    @AfterEach
    void backToTheFilePrinter() throws Exception {
        savePrinter(printerJson("FILE", 48, ""));
    }

    private void savePrinter(String json) throws Exception {
        mvc.perform(as(put("/api/hardware/printer/settings"), managerToken)
                        .header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private ResultActions printTestPage() throws Exception {
        return mvc.perform(as(post("/api/hardware/printer/test-page"), cashierToken)
                .header(LocalApiGuard.CLIENT_HEADER, "test"));
    }

    private static String printerJson(String type, int columns, String windowsPrinter) {
        return """
                {"type":"%s","serialPort":"","serialBaudRate":9600,"serialFlowControl":"NONE",
                 "tcpHost":"","tcpPort":9100,"windowsPrinter":"%s","windowsWidthMm":80,
                 "columns":%d,"codePage":"PC437","drawerPin":"PIN_2","drawerPulseMillis":100,
                 "timeoutSeconds":5}""".formatted(type, windowsPrinter, columns);
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
