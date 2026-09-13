package com.masspos.common;

import java.time.ZoneId;

/** GST dates and financial years are always Indian Standard Time, whatever zone the machine is set to. */
public final class IndiaTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private IndiaTime() {
    }
}
