package com.masspos.hardware;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

/**
 * This till's printer, as set on the settings screen. One row, seeded on the first start from
 * {@code pos.properties} so an installer can still pre-fill it, and owned by the screen after that.
 *
 * <p><b>Per machine, never shared.</b> Each till has its own database and its own printer, so this
 * row must stay out of counter/master sync: pushing the master's printer to every counter would
 * leave the shop printing bills on one machine.
 *
 * <p>Audited like every other entity, which also means the shop can see who changed the printer
 * and when - a useful thing to know when bills stop coming out.
 */
@Entity
@Audited
@Table(name = "printer_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_printer_settings_singleton", columnNames = "singleton"))
public class PrinterSettings extends BaseEntity {

    /** Always 1: a till has exactly one printer. */
    @Column(nullable = false)
    private int singleton = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PrinterProperties.Type type = PrinterProperties.Type.FILE;

    @Column(nullable = false, length = 60)
    private String serialPort = "";

    @Column(nullable = false)
    private int serialBaudRate = 9600;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PrinterProperties.FlowControl serialFlowControl = PrinterProperties.FlowControl.NONE;

    @Column(nullable = false, length = 100)
    private String tcpHost = "";

    @Column(nullable = false)
    private int tcpPort = 9100;

    @Column(nullable = false, length = 200)
    private String windowsPrinter = "";

    @Column(nullable = false)
    private int windowsWidthMm = 80;

    @Column(name = "print_columns", nullable = false)
    private int columns = 48;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CodePage codePage = CodePage.PC437;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EscPos.DrawerPin drawerPin = EscPos.DrawerPin.PIN_2;

    @Column(nullable = false)
    private int drawerPulseMillis = 100;

    @Column(nullable = false)
    private int timeoutSeconds = 5;

    public void apply(PrinterSettingsForm form) {
        this.type = form.type();
        this.serialPort = form.serialPort();
        this.serialBaudRate = form.serialBaudRate();
        this.serialFlowControl = form.serialFlowControl();
        this.tcpHost = form.tcpHost();
        this.tcpPort = form.tcpPort();
        this.windowsPrinter = form.windowsPrinter();
        this.windowsWidthMm = form.windowsWidthMm();
        this.columns = form.columns();
        this.codePage = form.codePage();
        this.drawerPin = form.drawerPin();
        this.drawerPulseMillis = form.drawerPulseMillis();
        this.timeoutSeconds = form.timeoutSeconds();
    }

    public PrinterSettingsForm toForm() {
        return new PrinterSettingsForm(type, serialPort, serialBaudRate, serialFlowControl, tcpHost, tcpPort,
                windowsPrinter, windowsWidthMm, columns, codePage, drawerPin, drawerPulseMillis, timeoutSeconds);
    }
}
