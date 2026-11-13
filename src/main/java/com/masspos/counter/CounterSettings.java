package com.masspos.counter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
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
import java.util.function.Supplier;

/**
 * The counter's own settings, in a plain window.
 *
 * <p>It cannot be a web page like the rest of the product, because everything it decides has to
 * be settled <em>before</em> there is a server to serve one. It is also the screen somebody opens
 * when the counter cannot connect, which is exactly when a web page would not load.
 *
 * <p>Two questions now, and only two: where is the shop's server, and which of its counters is
 * this machine. Both used to be typed. A counter that made up its own number could be a typo, or
 * two machines arguing over one number, and the shop's list of tills was only ever a list of
 * whatever had turned up. The counters belong to the shop, an admin keeps them on the server, and
 * this window picks one off that list.
 *
 * <p>The printer is not asked for here either. A new counter saves its bills to a file on this
 * machine until somebody chooses a printer on the printer page in the app itself - the screen
 * that can see this machine's ports and name the printers installed on it.
 */
final class CounterSettings {

    /** How often a counter that could not take its place asks again, while its window is open. */
    private static final int WATCH_MS = 4000;

    private CounterSettings() {
    }

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
     * <p>"Save and start billing" stays off until this counter could actually start billing: a
     * server that answers, a counter chosen from the list that server offers, and a place at that
     * server for it. The button being lit is a promise, and a promise this window cannot keep
     * sends somebody to a sign-in screen that will turn them away.
     *
     * @param trouble why the window is open again, in the server's words, or blank the first time
     * @return the saved settings, or empty if the person closed it without saving
     */
    static Optional<CounterConfig> edit(CounterConfig config, String trouble) {
        JDialog dialog = new JDialog((java.awt.Frame) null, "GO RockIt counter settings", true);
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
        JComboBox<ServerFinder.Choice> counters = new JComboBox<>();
        counters.setEnabled(false);
        counters.setPrototypeDisplayValue(
                new ServerFinder.Choice("", "T99", "A counter with a fairly long name", "", false, false, false));
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
        fields.add(heading("The shop's server"));
        fields.add(row(automatic, new JLabel("  a cable or Wi-Fi, either works")));
        fields.add(row(byAddress, address));
        fields.add(row(byUrl, url));
        fields.add(Box.createVerticalStrut(10));
        fields.add(heading("Which counter is this machine?"));
        fields.add(row(counters, new JLabel("  set up on the server, under Settings")));
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
            config.find(automatic.isSelected() ? CounterConfig.Find.AUTOMATIC
                    : byAddress.isSelected() ? CounterConfig.Find.ADDRESS : CounterConfig.Find.URL);
            config.address(address.getText());
            config.url(url.getText());
        };

        /*
         * What the server said, last time it was asked: where it is, and whether the counter
         * picked here could take its place. Both null until it has been asked, which is also what
         * every edit to where the server is puts them back to. `asked` counts the questions, so an
         * answer about a counter the person has since moved off is dropped rather than shown.
         */
        String[] server = {null};
        ServerFinder.Room[] room = {null};
        boolean[] looking = {false};
        boolean[] quietly = {false};
        int[] asked = {0};

        Supplier<ServerFinder.Choice> picked = () -> (ServerFinder.Choice) counters.getSelectedItem();

        Supplier<String> problem = () -> {
            if (config.find() == CounterConfig.Find.ADDRESS && config.address().isBlank()) {
                return "Type the server's name or IP address, or choose finding it on this network.";
            }
            if (config.find() == CounterConfig.Find.URL && config.url().isBlank()) {
                return "Type the server's full address, e.g. http://192.168.1.10:8765.";
            }
            if (server[0] == null) {
                return null;
            }
            if (counters.getItemCount() == 0) {
                return "This shop has no counters set up yet. On the server, open Settings, Tills in"
                        + " this shop, and add this counter - then look again.";
            }
            ServerFinder.Choice choice = picked.get();
            if (choice == null) {
                return "Choose which of the shop's counters this machine is.";
            }
            if (choice.overLimit()) {
                return "Counter " + choice.code() + " is beyond what this shop's licence covers, so"
                        + " nothing can bill on it. Choose another, or ask support to raise the limit.";
            }
            if (choice.blocked()) {
                return "Counter " + choice.code() + " has been blocked on the server. Choose another,"
                        + " or ask an admin to unblock it.";
            }
            return null;
        };

        Runnable refresh = () -> {
            String wrong = problem.get();
            ServerFinder.Choice choice = picked.get();
            boolean chosen = choice != null && !choice.blocked() && !choice.overLimit();
            boolean ready = wrong == null && server[0] != null && chosen
                    && room[0] != null && room[0].canJoin();
            save.setEnabled(ready);
            test.setEnabled(!looking[0]);
            counters.setEnabled(server[0] != null && counters.getItemCount() > 0);
            told.setText(wrap(looking[0] ? "Looking for the server..."
                    : wrong != null ? wrong
                    : server[0] == null ? "Press \"Look for the server\" to see this shop's counters."
                    : room[0] == null ? "Checking whether " + choice.code() + " can bill here..."
                    : room[0].canJoin() ? ready(choice, room[0])
                    : room[0].refusal() + "  Watching for a place to come free."));
        };

        /** Asks whether the counter now picked could bill here. Late answers are dropped. */
        Runnable askRoom = () -> {
            ServerFinder.Choice choice = picked.get();
            String at = server[0];
            if (choice == null || at == null) {
                return;
            }
            int mine = ++asked[0];
            new Thread(() -> {
                ServerFinder.Room answer = ServerFinder.roomFor(at, choice.id())
                        .orElseGet(() -> new ServerFinder.Room("", 0, 0, 0, false, false,
                                "Something answered at " + at + ", but it is not a GO RockIt shop"
                                        + " server. Check the address."));
                SwingUtilities.invokeLater(() -> {
                    if (mine != asked[0]) {
                        return;
                    }
                    room[0] = answer;
                    refresh.run();
                });
            }, "counter-ask-room").start();
        };

        /** Finds the server, fetches its counters, then asks about the one picked. */
        java.util.function.Consumer<Boolean> check = quiet -> {
            collect.run();
            if (!quiet) {
                looking[0] = true;
                server[0] = null;
                room[0] = null;
            }
            quietly[0] = quiet;
            refresh.run();
            new Thread(() -> {
                Optional<ServerFinder.Found> found = ServerFinder.find(config);
                List<ServerFinder.Choice> list = found
                        .map(at -> ServerFinder.countersAt(at.url()))
                        .orElse(List.of());
                SwingUtilities.invokeLater(() -> {
                    looking[0] = false;
                    quietly[0] = false;
                    if (found.isEmpty()) {
                        server[0] = null;
                        room[0] = null;
                        counters.setEnabled(false);
                        save.setEnabled(false);
                        told.setText(wrap(nothingAnswered(config)));
                        return;
                    }
                    server[0] = found.get().url();
                    String keep = picked.get() != null ? picked.get().id() : config.counterId();
                    counters.setModel(new DefaultComboBoxModel<>(list.toArray(new ServerFinder.Choice[0])));
                    select(counters, list, keep);
                    refresh.run();
                    askRoom.run();
                });
            }, "counter-find-server").start();
        };

        // Anything that changes which server is asked makes its whole answer worthless: the list
        // of counters and the place it offered both belonged to that server.
        Runnable askAgain = () -> {
            collect.run();
            server[0] = null;
            room[0] = null;
            asked[0]++;
            counters.setModel(new DefaultComboBoxModel<>());
            refresh.run();
        };
        onEdit(address, askAgain);
        onEdit(url, askAgain);
        for (JRadioButton choice : List.of(automatic, byAddress, byUrl)) {
            choice.addActionListener(event -> askAgain.run());
        }
        counters.addActionListener(event -> {
            if (server[0] == null) {
                return;
            }
            room[0] = null;
            refresh.run();
            askRoom.run();
        });

        test.addActionListener(event -> check.accept(false));

        save.addActionListener(event -> {
            collect.run();
            ServerFinder.Choice choice = picked.get();
            if (problem.get() != null || choice == null || room[0] == null || !room[0].canJoin()) {
                // Belt and braces: the button is off in all of these. Keyboard shortcuts and the
                // dialog's default button have been known to fire one anyway.
                refresh.run();
                return;
            }
            config.counter(choice.id(), choice.code(), choice.name());
            try {
                config.save();
                saved[0] = true;
                dialog.dispose();
            } catch (IOException cannotWrite) {
                told.setText(wrap("Could not save to " + CounterConfig.file() + ": "
                        + cannotWrite.getMessage()));
            }
        });
        cancel.addActionListener(event -> dialog.dispose());

        collect.run();
        refresh.run();
        // Nobody should have to press anything to get started: the window asks as it opens, and
        // the usual case - one server, one free counter - is ready by the time it is on screen.
        if (problem.get() == null) {
            check.accept(false);
        }

        /*
         * A counter turned away because the shop was full should not need anybody to press
         * anything: somebody logging out across the room frees a place at once, and a counter
         * added on the server appears in this list. So it asks itself, quietly.
         */
        Timer watching = new Timer(WATCH_MS, event -> {
            if (!looking[0] && (server[0] == null || room[0] == null || !room[0].canJoin())) {
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
     * Keeps the counter this machine already is, if the shop still has it; otherwise the first one
     * nobody is on, so the usual case needs no choosing at all.
     */
    static void select(JComboBox<ServerFinder.Choice> counters, List<ServerFinder.Choice> list,
                       String keepId) {
        list.stream()
                .filter(choice -> choice.id().equals(keepId))
                .findFirst()
                .or(() -> list.stream().filter(ServerFinder.Choice::free).findFirst())
                .ifPresent(counters::setSelectedItem);
    }

    private static String nothingAnswered(CounterConfig config) {
        if (config.find() == CounterConfig.Find.AUTOMATIC) {
            return "No shop server answered. On the server: is pos.clients.max above 0, did Windows"
                    + " Firewall allow GO RockIt, and is the network set to Private rather than Public?"
                    + " Or type the server's address above instead.";
        }
        return "Nothing answered there. Check the address, and that the server is running.";
    }

    private static String ready(ServerFinder.Choice choice, ServerFinder.Room room) {
        String shop = room.shop() == null || room.shop().isBlank() ? "the shop's server" : room.shop();
        String places = room.places() == 0 ? ""
                : room.holdsPlace() ? " It is already billing here."
                : " " + room.free() + " of " + room.places() + " places free.";
        return "Ready: this machine will bill as " + choice.code() + " " + choice.name()
                + " at " + shop + "." + places;
    }

    /** Tells somebody what went wrong in a window, for the times there is no screen to put it on. */
    static void say(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE);
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
}
