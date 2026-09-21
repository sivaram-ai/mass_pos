package com.masspos.license;

/** This computer's hardware fingerprint, which a licence is bound to. */
public interface MachineIdentity {

    /**
     * Four groups of four characters, e.g. {@code K7Q2-9XMA-D3RT-0BNW}, the same every time on the same
     * hardware and Windows installation.
     *
     * @throws IllegalStateException when the hardware IDs cannot be read
     */
    String machineId();
}
