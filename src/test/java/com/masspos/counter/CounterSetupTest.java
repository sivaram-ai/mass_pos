package com.masspos.counter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the counter settings page is told about the machine it is open on.
 *
 * <p>The page is reached two ways and they are not the same errand. A shop that refuses the
 * machine sends it here to be put right, and there is nothing to go back to until it is. Somebody
 * at a counter that is already billing opens it from the sign-in screen - to point this machine at
 * another shop, to change how it finds the one it has, or simply to read what it is set to - and
 * for them, leaving without changing anything has to be one of the answers.
 */
class CounterSetupTest {

    private final ObjectMapper json = new ObjectMapper();

    /** The page opens on what is already set, not on an empty form the person has to re-fill. */
    @Test
    void opensOnTheSettingsThisCounterIsRunningOn() throws IOException {
        CounterConfig counter = new CounterConfig();
        counter.find(CounterConfig.Find.ADDRESS);
        counter.address("192.168.1.10");
        counter.counter("0193f0c2-7e1a-7c3d-8f21-6b4a1d9e5c70", "T2", "Counter 2");

        JsonNode state = json.readTree(CounterSetup.state(counter));

        assertThat(state.get("find").asText()).isEqualTo("ADDRESS");
        assertThat(state.get("address").asText()).isEqualTo("192.168.1.10");
        assertThat(state.get("counterId").asText()).isEqualTo("0193f0c2-7e1a-7c3d-8f21-6b4a1d9e5c70");
        assertThat(state.get("counterCode").asText()).isEqualTo("T2");
    }

    /**
     * The way back is the billing screen on the server this counter last used, which is the same
     * address the counter app opens on its own - a shop nobody is signed in on answers it with the
     * sign-in screen, so the one address serves both. It carries this counter's id, because that
     * screen is served by the shop and has to be told which of its counters is asking.
     */
    @Test
    void offersTheWayBackToACounterThatIsAlreadyBilling() throws IOException {
        CounterConfig counter = new CounterConfig();
        counter.counter("0193f0c2-7e1a-7c3d-8f21-6b4a1d9e5c70", "T2", "Counter 2");
        counter.lastServer("http://192.168.1.10:8765");

        JsonNode state = json.readTree(CounterSetup.state(counter));

        assertThat(state.get("billing").asText())
                .startsWith("http://192.168.1.10:8765/?counter=0193f0c2-7e1a-7c3d-8f21-6b4a1d9e5c70")
                .contains("counterCode=T2");
    }

    /**
     * And none to a counter that has never reached a shop. A button that led to an address nothing
     * answers would be worse than no button: this page is the only screen such a machine has.
     */
    @Test
    void offersNoWayBackBeforeThisCounterHasEverFoundItsShop() throws IOException {
        JsonNode state = json.readTree(CounterSetup.state(new CounterConfig()));

        assertThat(state.get("billing").asText()).isEmpty();
    }

    /** Told which counter it is, but not yet where the shop is: still nowhere to go back to. */
    @Test
    void offersNoWayBackToACounterWhoseServerIsNotKnown() throws IOException {
        CounterConfig counter = new CounterConfig();
        counter.counter("0193f0c2-7e1a-7c3d-8f21-6b4a1d9e5c70", "T2", "Counter 2");

        JsonNode state = json.readTree(CounterSetup.state(counter));

        assertThat(state.get("billing").asText()).isEmpty();
    }
}
