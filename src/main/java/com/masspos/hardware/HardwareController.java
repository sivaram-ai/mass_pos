package com.masspos.hardware;

import com.fazecast.jSerialComm.SerialPort;
import com.masspos.auth.AuthContext;
import com.masspos.auth.RequiresRole;
import com.masspos.settings.ShopSettingsService;
import com.masspos.common.IndiaTime;
import com.masspos.config.PosProperties;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/hardware")
public class HardwareController {

    private static final Logger log = LoggerFactory.getLogger(HardwareController.class);
    private static final DateTimeFormatter PRINTED_AT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final ThermalPrinter printer;
    private final PrinterSettingsService printerSettings;
    private final PosProperties pos;
    private final ShopSettingsService settings;

    public HardwareController(ThermalPrinter printer, PrinterSettingsService printerSettings, PosProperties pos,
                              ShopSettingsService settings) {
        this.printer = printer;
        this.printerSettings = printerSettings;
        this.pos = pos;
        this.settings = settings;
    }

    public record SerialPortInfo(String systemName, String description, String details) {
    }

    public record DrawerRequest(@NotBlank @Size(max = 100) String reason) {
    }

    /**
     * Everything the printer page needs in one call: how this till is set up, where that sends
     * bills, and what is plugged into this machine to choose from.
     *
     * @param target where jobs are going now, e.g. "WINDOWS Canon G3080 series"
     */
    public record PrinterSetupView(PrinterSettingsForm printer, String target, List<SerialPortInfo> serialPorts,
                                   List<String> installedPrinters) {
    }

    /** For the printer setup screen: which ports exist on this machine right now. */
    @GetMapping("/serial-ports")
    @RequiresRole({UserRole.MANAGER})
    public List<SerialPortInfo> serialPorts() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(p -> new SerialPortInfo(p.getSystemPortName(), p.getDescriptivePortName(), p.getPortDescription()))
                .toList();
    }

    @GetMapping("/printer/settings")
    @RequiresRole({UserRole.MANAGER})
    @Transactional(readOnly = true)
    public PrinterSetupView printerSettings() {
        return setupView(printerSettings.current().toForm());
    }

    /**
     * Takes effect on the next job: the old connection is closed and the new printer opened when
     * the next bill is printed, so there is nothing to restart.
     */
    @PutMapping("/printer/settings")
    @RequiresRole({UserRole.MANAGER})
    @Transactional
    public PrinterSetupView savePrinterSettings(@Valid @RequestBody PrinterSettingsForm form) {
        return setupView(printerSettings.update(form).toForm());
    }

    private PrinterSetupView setupView(PrinterSettingsForm form) {
        return new PrinterSetupView(form, printer.target(), serialPorts(), installedPrinters());
    }

    /** Printers installed in the operating system, for the WINDOWS type. */
    private static List<String> installedPrinters() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(PrintService::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Any signed-in user: the billing screen shows paper and cover state. */
    @GetMapping("/printer/status")
    public PrinterStatus printerStatus() {
        return printer.await(printer.status());
    }

    @PostMapping("/printer/test-page")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void printTestPage() {
        printer.await(printer.print(testPage()));
    }

    /** "No sale" opening. Cash sales open the drawer through their receipt instead. */
    @PostMapping("/drawer/open")
    @RequiresRole({UserRole.CASHIER, UserRole.MANAGER})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void openDrawer(@Valid @RequestBody DrawerRequest request) {
        log.info("Cash drawer opened without a sale by {}: {}", AuthContext.require().username(),
                request.reason().replaceAll("\\p{Cntrl}", " "));
        printer.await(printer.openDrawer());
    }

    private EscPos testPage() {
        PrinterProperties setup = printer.settings();
        EscPos page = printer.newDocument();
        StringBuilder ruler = new StringBuilder();
        for (int i = 1; i <= page.columns(); i++) {
            ruler.append(i % 10);
        }
        String name = settings.currentOrEmpty().displayName();
        String shop = name.isEmpty() ? "GO RockIt" : name;
        return page.align(EscPos.Align.CENTER)
                .size(true, true).bold(true).line("TEST PAGE").size(false, false).bold(false)
                .wrapped(shop)
                .line("%s (%s)".formatted(pos.terminal().name(), pos.terminal().code()))
                .align(EscPos.Align.LEFT)
                .separator('=')
                .line(ruler.toString())
                .leftRight("Printer", printer.target())
                .leftRight("Code page", setup.codePage().name())
                .leftRight("Columns", String.valueOf(setup.columns()))
                .leftRight("Printed", PRINTED_AT.format(ZonedDateTime.now(IndiaTime.ZONE)))
                .line("Rupee sign prints as: ₹")
                .separator('=')
                .cut();
    }
}
