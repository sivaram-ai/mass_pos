package com.masspos.client;

/** What a counter shouts and what a server shouts back. Shared so the two cannot drift apart. */
public final class Discovery {

    /** Sent by a counter looking for its shop's server, as a UDP broadcast. */
    public static final String ASK = "GO-ROCKIT-WHERE-IS-THE-SERVER?1";

    /** First field of the answer, so a stray datagram on the port is not mistaken for one. */
    public static final String ANSWER = "GO-ROCKIT-SERVER?1";

    /** Answer fields, after {@link #ANSWER}: the port to bill on, the shop's name, places free. */
    public static final String SEPARATOR = "\u001f";

    private Discovery() {
    }

    public static String answer(int port, String shopName, int free, int places) {
        return String.join(SEPARATOR, ANSWER, Integer.toString(port), shopName,
                Integer.toString(free), Integer.toString(places));
    }
}
