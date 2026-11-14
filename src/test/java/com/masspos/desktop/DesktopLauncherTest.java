package com.masspos.desktop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopLauncherTest {

    @Test
    void aTillAlreadyServingIsFoundOnItsPort() throws IOException {
        try (ServerSocket till = new ServerSocket()) {
            till.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            assertThat(DesktopLauncher.isUp(till.getLocalPort())).isTrue();
        }
    }

    @Test
    void aPortNothingIsListeningOnMeansTheTillHasToBeStarted() throws IOException {
        int free;
        try (ServerSocket closed = new ServerSocket()) {
            closed.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            free = closed.getLocalPort();
        }

        assertThat(DesktopLauncher.isUp(free)).isFalse();
    }

    @Test
    void aPlainPathIsLeftAlone() {
        assertThat(Browser.expand("C:/Program Files/Microsoft/msedge.exe"))
                .isEqualTo(Path.of("C:/Program Files/Microsoft/msedge.exe"));
    }

    @Test
    void aBrowserUnderAFolderThisMachineDoesNotHaveIsSkipped() {
        assertThat(Browser.expand("%NO_SUCH_FOLDER_ON_ANY_TILL%/msedge.exe")).isNull();
    }

    @Test
    void aLauncherPackagedAsTheServerSaysSoInsteadOfStartingAnotherOne() {
        assertThatThrownBy(() -> DesktopLauncher.refuseIfStartedAsTheServer("1"))
                .isInstanceOf(DesktopLauncher.Refusal.class)
                .hasMessageContaining("not installed properly")
                // What is wrong is a service question. The person holding the mouse is told the
                // one thing they can do about it.
                .hasMessageNotContaining(".exe")
                .hasMessageNotContaining("launcher");
    }

    @Test
    void anOrdinaryClickOnTheShortcutCarriesNoSuchMark() {
        assertThatCode(() -> DesktopLauncher.refuseIfStartedAsTheServer(null)).doesNotThrowAnyException();
    }
}
