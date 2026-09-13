package com.masspos.auth;

import java.util.Set;

/** PIN rules: short enough to type at a busy counter, not so guessable that anyone can void a sale. */
final class Pins {

    private static final Set<String> TOO_COMMON = Set.of(
            "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
            "1234", "4321", "1212", "2580", "0852", "123456", "654321", "111111", "000000");

    private Pins() {
    }

    static void check(String pin) {
        if (pin == null || !pin.matches("\\d{4,8}")) {
            throw new IllegalArgumentException("PIN must be 4 to 8 digits");
        }
        if (TOO_COMMON.contains(pin)) {
            throw new IllegalArgumentException("That PIN is too easy to guess, pick another");
        }
    }
}
