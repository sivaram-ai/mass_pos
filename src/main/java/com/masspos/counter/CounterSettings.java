package com.masspos.counter;

import com.masspos.hardware.PrinterProperties;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The counter's own settings, in a plain window.
 *
 * <p>It cannot be a web page like the rest of the product, because everything it decides has to
 * be settled <em>before</em> there is a server to serve one. It is also the screen somebody opens
 * when the counter cannot connect, which is exactly when a web page would not load.
 *
 * <p>Two questions, in the order a person installing a till asks them: which counter is this, and
 * where is the shop's server. The printer below them is the counter's own, because the bill comes
 * out here.
 */
final class CounterSettings {

    private CounterSettings() {
    }

    /**
     * Opens the window and blocks until it is closed.
     *
     * @return the saved settings, or empty if the person closed it without saving
     */
    static Optional<CounterConfig> edit(CounterConfig config) {
        JDialog dialog = new JDialog((java.awt.Frame) null, "GO RockIt counter settings", true);
        JTextField code = new JTextField(config.code(), 6);
        JTextField name = new JTextField(config.name(), 18);
        JRadioButton automatic = new JRadioButton("Find the server on this network",
                config.find() == CounterConfig.Find.AUTOMATIC);
        JRadioButton byAddress = new JRadioButton("The server's name or IP address",
                config.find() == CounterConfig.Find.ADDRESS);
        JRadioButton byUrl = new JRadioButton("A full address", config.find() == CounterConfig.Find.URL);
        ButtonGroup how = new ButtonGroup();
        how.add(automatic);
        how.add(byAddress);
        how.add(byUrl);
        JTextField address = new JTextField(config.address(), 20);
        JTextField url = new JTextField(config.url().isBlank() ? "http://" : config.url(), 24);
        JComboBox<PrinterProperties.Type> printerType =
                new JComboBox<>(PrinterProperties.Type.values());
        printerType.setSelectedItem(config.printerType());
        JTextField windowsPrinter = new JTextField(config.windowsPrinter(), 20);
        JTextField serialPort = new JTextField(config.serialPort(), 8);
        JTextField tcpHost = new JTextField(config.tcpHost(), 14);
        JTextField tcpPort = new JTextField(Integer.toString(config.tcpPort()), 6);
        JTextField columns = new JTextField(Integer.toString(config.columns()), 4);
        JLabel told = new JLabel(" ");

        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        fields.add(heading("This counter"));
        fields.add(row(new JLabel("Counter number"), code,
                new JLabel("  up to 3 characters, e.g. T2 - it goes on every bill")));
        fields.add(row(new JLabel("Name"), name, new JLabel("  what staff call it, e.g. Counter 2")));
        fields.add(Box.createVerticalStrut(10));
        fields.add(heading("The shop's server"));
        fields.add(row(automatic, new JLabel("  a cable or Wi-Fi, either works")));
        fields.add(row(byAddress, address));
        fields.add(row(byUrl, url));
        fields.add(Box.createVerticalStrut(10));
        fields.add(heading("The printer on this counter"));
        fields.add(row(new JLabel("Type"), printerType, new JLabel("  WINDOWS uses an installed printer")));
        fields.add(row(new JLabel("Windows printer"), windowsPrinter, new JLabel("  blank = the default")));
        fields.add(row(new JLabel("COM port"), serialPort, new JLabel("Network printer"), tcpHost, tcpPort));
        fields.add(row(new JLabel("Characters per line"), columns, new JLabel("  48 for 80mm, 32 for 58mm")));
        fields.add(Box.createVerticalStrut(8));
        fields.add(row(told));

        JButton test = new JButton("Look for the server");
        JButton save = new JButton("Save and start billing");
        JButton cancel = new JButton("Close");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(test);
        buttons.add(cancel);
        buttons.add(save);

        boolean[] saved = {false};
        Runnable collect = () -> {
            config.code(code.getText());
            config.name(name.getText());
            config.find(automatic.isSelected() ? CounterConfig.Find.AUTOMATIC
                    : byAddress.isSelected() ? CounterConfig.Find.ADDRESS : CounterConfig.Find.URL);
            config.address(address.getText());
            config.url(url.getText());
            config.printerType((PrinterProperties.Type) printerType.getSelectedItem());
            config.windowsPrinter(windowsPrinter.getText());
            config.serialPort(serialPort.getText());
            config.tcpHost(tcpHost.getText());
            config.tcpPort(number(tcpPort.getText(), 9100));
            config.columns(number(columns.getText(), 48));
        };

        test.addActionListener(event -> {
            collect.run();
            told.setText("Looking...");
            // On a worker thread: a shout waits over a second, and a frozen window looks broken.
            new Thread(() -> {
                String answer = describeWhatIsOutThere(config);
                SwingUtilities.invokeLater(() -> told.setText(answer));
            }, "counter-find-server").start();
        });

        save.addActionListener(event -> {
            collect.run();
            if (config.code().isBlank()) {
                told.setText("Give this counter a number first - it goes on every bill it takes.");
                return;
            }
            if (!config.readyToConnect()) {
                told.setText("Fill in where the server is, or choose finding it on this network.");
                return;
            }
            try {
                config.save();
                saved[0] = true;
                dialog.dispose();
            } catch (IOException cannotWrite) {
                told.setText("Could not save to " + CounterConfig.file() + ": " + cannotWrite.getMessage());
            }
        });
        cancel.addActionListener(event -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(save);
        dialog.setLayout(new BorderLayout());
        dialog.add(fields, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(640, dialog.getHeight()));
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return saved[0] ? Optional.of(config) : Optional.empty();
    }

    /** What the settings screen says after "Look for the server", in one sentence. */
    private static String describeWhatIsOutThere(CounterConfig config) {
        if (config.find() == CounterConfig.Find.AUTOMATIC) {
            List<ServerFinder.Found> servers = ServerFinder.discover();
            if (servers.isEmpty()) {
                return "<html>No shop server answered.<br>On the server: is pos.clients.max above 0,"
                        + " did Windows Firewall allow GO RockIt, and is the network set to Private?"
                        + "<br>Or type the server's address above instead.</html>";
            }
            if (servers.size() > 1) {
                return servers.size() + " servers answered. Type the address of the right one: "
                        + servers.stream().map(ServerFinder.Found::describe).reduce((a, b) -> a + "; " + b).orElse("");
            }
            ServerFinder.Found only = servers.get(0);
            return only.full()
                    ? "Found " + only.describe() + ", but it has no places left for another counter."
                    : "Found " + only.describe() + ".";
        }
        return ServerFinder.find(config)
                .map(found -> "That server answered. Save and start billing.")
                .orElse("Nothing answered there. Check the address, and that the server is running.");
    }

    /** Tells somebody what went wrong in a window, for the times there is no screen to put it on. */
    static void say(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private static JPanel heading(String text) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(label.getFont().getStyle() | java.awt.Font.BOLD));
        panel.add(label);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JPanel row(Component... parts) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component part : parts) {
            if (part instanceof JLabel label && label.getText().startsWith(" ")) {
                label.setEnabled(false);
            }
            panel.add(part);
        }
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
