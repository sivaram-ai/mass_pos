package com.masspos.counter;

import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing which of the shop's counters this machine is.
 *
 * <p>The window used to ask somebody to type a number and a name, and the first sign-in created
 * whatever they had typed. The counters are the shop's now - an admin keeps them on the server -
 * so all this window does is offer the list and remember the choice.
 */
class CounterSettingsTest {

    private static final String FIRST = "01a1cb62-d9d7-75cd-be17-fa6db596caa3";
    private static final String SECOND = "01a1cb63-aaaa-75cd-be17-fa6db596caa4";

    /** The number first, because that is what is written on the till somebody is standing at. */
    @Test
    void showsACounterTheWayItIsWrittenOnTheTill() {
        assertThat(choice(FIRST, "T2", "Counter 2", "", false, false)).hasToString("T2  Counter 2");
        assertThat(choice(FIRST, "T2", "Counter 2", "1st floor", false, false))
                .hasToString("T2  Counter 2 - 1st floor");
        assertThat(choice(FIRST, "T2", "Counter 2", "", true, false)).hasToString("T2  Counter 2  (in use)");
        assertThat(choice(FIRST, "T2", "Counter 2", "", false, true)).hasToString("T2  Counter 2  (blocked)");
    }

    /** Only a counter nobody is on, and that nobody has blocked, is one this machine could be. */
    @Test
    void countsACounterAsFreeOnlyWhenItIsBothUnusedAndUnblocked() {
        assertThat(choice(FIRST, "T2", "Counter 2", "", false, false).free()).isTrue();
        assertThat(choice(FIRST, "T2", "Counter 2", "", true, false).free()).isFalse();
        assertThat(choice(FIRST, "T2", "Counter 2", "", false, true).free()).isFalse();
    }

    /**
     * A counter that is already set up comes back to the same till, even when another one is free
     * - this machine has been billing as that counter and its bills are filed under that number.
     */
    @Test
    void comesBackToTheCounterThisMachineAlreadyIs() {
        List<ServerFinder.Choice> list = List.of(
                choice(FIRST, "T2", "Counter 2", "", false, false),
                choice(SECOND, "T3", "Counter 3", "", false, false));
        JComboBox<ServerFinder.Choice> box = boxOf(list);

        CounterSettings.select(box, list, SECOND);

        assertThat(box.getSelectedItem()).isEqualTo(list.get(1));
    }

    /** A machine being set up for the first time lands on a counter nobody else is on. */
    @Test
    void offersTheFirstFreeCounterToAMachineThatHasNoneYet() {
        List<ServerFinder.Choice> list = List.of(
                choice(FIRST, "T2", "Counter 2", "", true, false),
                choice(SECOND, "T3", "Counter 3", "", false, false));
        JComboBox<ServerFinder.Choice> box = boxOf(list);

        CounterSettings.select(box, list, "");

        assertThat(box.getSelectedItem()).isEqualTo(list.get(1));
    }

    /**
     * Every counter taken or blocked: nothing is chosen for the person, so the window asks rather
     * than quietly offering a counter that cannot be used.
     */
    @Test
    void choosesNothingWhenNoCounterIsFree() {
        List<ServerFinder.Choice> list = List.of(
                choice(FIRST, "T2", "Counter 2", "", true, false),
                choice(SECOND, "T3", "Counter 3", "", false, true));
        JComboBox<ServerFinder.Choice> box = boxOf(list);
        box.setSelectedItem(null);

        CounterSettings.select(box, list, "");

        assertThat(box.getSelectedItem()).isNull();
    }

    private static ServerFinder.Choice choice(String id, String code, String name, String section,
                                              boolean taken, boolean blocked) {
        return new ServerFinder.Choice(id, code, name, section, taken, blocked, false);
    }

    /** A counter the shop has but its licence does not cover: listed, marked, and not pickable. */
    @Test
    void marksACounterTheLicenceDoesNotCover() {
        ServerFinder.Choice over = new ServerFinder.Choice(FIRST, "T2", "Counter 2", "", false, false, true);

        assertThat(over).hasToString("T2  Counter 2  (beyond the shop licence)");
        assertThat(over.free()).isFalse();
    }

    private static JComboBox<ServerFinder.Choice> boxOf(List<ServerFinder.Choice> list) {
        return new JComboBox<>(list.toArray(new ServerFinder.Choice[0]));
    }
}
