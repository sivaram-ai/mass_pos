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
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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

    /** How often a counter that was told the shop is full asks again, while its window is open. */
    private static final int WATCH_MS = 4000;

    /** Characters per line, as the server's own printer page allows. */
    private static final int MIN_COLUMNS = 24;
    private static final int MAX_COLUMNS = 64;

    /**
     * Opens the window and blocks until it is closed.
     *
     * @return the saved settings, or empty if the person closed it without saving
     */
    static Optional<CounterConfig> edit(CounterConfig config) {
        return edit(config, "");
    }

    /**
     * Opens the window and blocks until it is closed.
     *
     * <p>"Save and start billing" stays off until this counter could actually start billing: every
     * field filled in and sane, a server that answers, and a place at that server for this counter.
     * The last of those cannot be worked out here - only the server knows how many machines are
     * signed in - so the window asks it, and asks again whenever anything that would change the
     * answer is edited. The button being lit is a promise, and a promise this window cannot keep
     * sends somebody to a sign-in screen that will turn them away.
     *
     * @param trouble why the window is open again, in the server's words, or blank the first time
     * @return the saved settings, or empty if the person closed it without saving
     */
    static Optional<CounterConfig> edit(CounterConfig config, String trouble) {
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
        if (trouble != null && !trouble.isBlank()) {
            JLabel sent = new JLabel(wrap("The shop's server turned this counter away: " + trouble));
            sent.setForeground(new java.awt.Color(0xB1, 0x1C, 0x3C));
            fields.add(row(sent));
            fields.add(Box.createVerticalStrut(8));
        }
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
        save.setEnabled(false);
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

        /*
         * Typed nonsense in a number box is its own problem, and has to be caught from the text:
         * `collect` falls back to a sane number so the config never holds rubbish, which would
         * otherwise make "abc" look like a perfectly good 48 to the range checks.
         */
        java.util.function.Supplier<String> problem = () -> {
            if (!isWholeNumber(columns.getText())) {
                return "Characters per line has to be a number - 48 for an 80mm roll, 32 for 58mm.";
            }
            if (config.printerType() == PrinterProperties.Type.TCP && !isWholeNumber(tcpPort.getText())) {
                return "The network printer's port has to be a number, usually 9100.";
            }
            return problemWith(config);
        };

        /*
         * What the server last said about taking this counter: null until it has been asked,
         * which is also what every edit that would change the answer puts it back to. Touched only
         * on the event thread, except where the worker hands it back through invokeLater.
         */
        ServerFinder.Room[] room = {null};
        boolean[] looking = {false};
        /** True while a check the window started by itself is in flight, so it says nothing. */
        boolean[] waiting = {false};

        Runnable refresh = () -> {
            String wrong = problem.get();
            boolean ready = wrong == null && room[0] != null && room[0].canJoin();
            save.setEnabled(ready);
            test.setEnabled(wrong == null && !looking[0]);
            told.setText(wrap(looking[0] ? "Looking for the server..."
                    : wrong != null ? wrong
                    : room[0] == null ? "Press \"Look for the server\" to check this shop has a place for "
                            + config.code() + "."
                    : room[0].canJoin() ? ready(config, room[0])
                    : room[0].refusal() + "  Watching for a place to come free."));
        };

        /*
         * `quiet` is the difference between somebody pressing the button and the window looking
         * again on its own: a quiet check leaves the last answer on screen until it has a new one,
         * so a counter waiting for a place does not flicker "Looking for the server..." at nobody
         * every few seconds.
         */
        java.util.function.Consumer<Boolean> check = quiet -> {
            collect.run();
            if (problem.get() != null) {
                refresh.run();
                return;
            }
            if (!quiet) {
                looking[0] = true;
                room[0] = null;
            }
            waiting[0] = quiet;
            refresh.run();
            // On a worker thread: a shout waits over a second, and a frozen window looks broken.
            new Thread(() -> {
                ServerFinder.Room answer = askTheServer(config);
                SwingUtilities.invokeLater(() -> {
                    looking[0] = false;
                    room[0] = answer;
                    waiting[0] = false;
                    refresh.run();
                });
            }, "counter-find-server").start();
        };
        Runnable lookNow = () -> check.accept(false);

        // Anything that changes which server is asked, or who is asking, makes the last answer
        // worthless: the button goes out until the new question has been put to the server.
        Runnable askAgain = () -> {
            collect.run();
            room[0] = null;
            refresh.run();
        };
        Runnable recheck = () -> {
            collect.run();
            refresh.run();
        };
        onEdit(code, askAgain);
        onEdit(address, askAgain);
        onEdit(url, askAgain);
        for (JRadioButton choice : List.of(automatic, byAddress, byUrl)) {
            choice.addActionListener(event -> askAgain.run());
        }
        onEdit(name, recheck);
        onEdit(windowsPrinter, recheck);
        onEdit(serialPort, recheck);
        onEdit(tcpHost, recheck);
        onEdit(tcpPort, recheck);
        onEdit(columns, recheck);
        printerType.addActionListener(event -> recheck.run());

        test.addActionListener(event -> lookNow.run());

        save.addActionListener(event -> {
            collect.run();
            if (problem.get() != null || room[0] == null || !room[0].canJoin()) {
                // Belt and braces: the button is off in all three cases. Keyboard shortcuts and
                // the dialog's default button have been known to fire one anyway.
                refresh.run();
                return;
            }
            try {
                config.save();
                saved[0] = true;
                dialog.dispose();
            } catch (IOException cannotWrite) {
                told.setText(wrap("Could not save to " + CounterConfig.file() + ": " + cannotWrite.getMessage()));
            }
        });
        cancel.addActionListener(event -> dialog.dispose());

        collect.run();
        refresh.run();
        // A counter that was already set up should not have to be asked to press anything: its
        // settings are the ones that were working, and the only open question is whether the shop
        // still has room. Ask straight away, and the window opens with the answer already on it.
        if (problem.get() == null) {
            lookNow.run();
        }

        /*
         * A counter turned away because the shop was full should not need anybody to press
         * anything: somebody logging out across the room frees a place at once, and the only thing
         * standing between this machine and billing is asking again. So it asks itself, and the
         * button lights up on its own within a few seconds of the place coming free.
         */
        Timer watching = new Timer(WATCH_MS, event -> {
            if (!looking[0] && !waiting[0] && problem.get() == null && room[0] != null && !room[0].canJoin()) {
                check.accept(true);
            }
        });
        watching.start();

        dialog.getRootPane().setDefaultButton(save);
        dialog.setLayout(new BorderLayout());
        dialog.add(fields, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(640, dialog.getHeight()));
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        // Modal, so this is reached once the window has gone: nothing left to watch for.
        watching.stop();
        return saved[0] ? Optional.of(config) : Optional.empty();
    }

    /**
     * The first thing wrong with what is on the window, in the words somebody setting a counter up
     * would use, or null when everything here is fit to save. Only what this machine can judge by
     * itself: whether the shop has a place for it is the server's answer, not this one's.
     */
    static String problemWith(CounterConfig config) {
        String badCode = CounterConfig.codeProblem(config.code());
        if (badCode != null) {
            return badCode;
        }
        if (config.rawName().isBlank()) {
            return "Give this counter a name - it is what staff see on every screen, e.g. Counter 2.";
        }
        if (config.find() == CounterConfig.Find.ADDRESS && config.address().isBlank()) {
            return "Type the server's name or IP address, or choose finding it on this network.";
        }
        if (config.find() == CounterConfig.Find.URL && config.url().isBlank()) {
            return "Type the server's full address, e.g. http://192.168.1.10:8765.";
        }
        return printerProblem(config);
    }

    /** The same ranges the agent holds a saved printer to, asked here before anything is saved. */
    private static String printerProblem(CounterConfig config) {
        if (config.printerType() == PrinterProperties.Type.SERIAL && config.serialPort().isBlank()) {
            return "Which COM port is the printer on? e.g. COM3.";
        }
        if (config.printerType() == PrinterProperties.Type.TCP && config.tcpHost().isBlank()) {
            return "Type the network printer's name or IP address.";
        }
        if (config.printerType() == PrinterProperties.Type.TCP
                && (config.tcpPort() < 1 || config.tcpPort() > 65535)) {
            return "The printer's port has to be between 1 and 65535 - 9100 on most network printers.";
        }
        if (config.columns() < MIN_COLUMNS || config.columns() > MAX_COLUMNS) {
            return "Characters per line has to be between " + MIN_COLUMNS + " and " + MAX_COLUMNS
                    + " - 48 for an 80mm roll, 32 for 58mm.";
        }
        return null;
    }

    /** What the server answered about taking this counter, or a made-up refusal when none did. */
    private static ServerFinder.Room askTheServer(CounterConfig config) {
        Optional<ServerFinder.Found> server = ServerFinder.find(config);
        if (server.isEmpty()) {
            return new ServerFinder.Room("", 0, 0, 0, false, false, nothingAnswered(config));
        }
        return ServerFinder.roomFor(server.get().url(), config.code())
                .orElseGet(() -> new ServerFinder.Room("", 0, 0, 0, false, false,
                        "Something answered at " + server.get().url() + ", but it is not a GO RockIt shop"
                                + " server. Check the address."));
    }

    private static String nothingAnswered(CounterConfig config) {
        if (config.find() == CounterConfig.Find.AUTOMATIC) {
            return "No shop server answered. On the server: is pos.clients.max above 0, did Windows"
                    + " Firewall allow GO RockIt, and is the network set to Private rather than Public?"
                    + " Or type the server's address above instead.";
        }
        return "Nothing answered there. Check the address, and that the server is running.";
    }

    private static String ready(CounterConfig config, ServerFinder.Room room) {
        String shop = room.shop() == null || room.shop().isBlank() ? "the shop's server" : room.shop();
        String places = room.places() == 0 ? ""
                : room.holdsPlace() ? " " + config.code() + " is already one of its counters."
                : " " + room.free() + " of " + room.places() + " places free.";
        return "Ready: " + config.code() + " can bill at " + shop + "." + places;
    }

    /** Runs on every keystroke, so the button and the line underneath never lag behind the fields. */
    private static void onEdit(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        });
    }

    /** A sentence long enough to need two lines, in a label that only wraps when told to. */
    private static String wrap(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body style='width:560px'>" + escaped + "</body></html>";
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

    private static boolean isWholeNumber(String text) {
        try {
            Integer.parseInt(text.trim());
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }
}
