package com.masspos.terminal;

public enum TerminalType {
    /** Holds the shop's consolidated database; counters replicate to it. */
    MASTER,
    /** A billing counter. Bills on its own and syncs to the master when reachable. */
    COUNTER
}
