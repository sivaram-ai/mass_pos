package com.masspos;

import com.masspos.auth.AuthService;
import com.masspos.terminal.TerminalRepository;
import com.masspos.user.UserRepository;
import com.masspos.user.UserRole;
import com.masspos.web.LocalApiGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"pos.data-dir=" + AuthApiIntegrationTest.DATA_DIR,
        "pos.auth.initial-admin-pin=481920", "pos.terminal.name=Counter 2", "pos.terminal.section=1st floor"})
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/auth-it";

    static {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AuthService auth;
    @Autowired
    UserRepository users;
    @Autowired
    TerminalRepository terminals;

    @Test
    void firstStartCreatesAnAdminThatMustPickItsOwnPin() throws Exception {
        String token = mvc.perform(login("admin", "481920"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.mustChangePin").value(true))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // Nothing but changing the PIN works until it is changed.
        mvc.perform(as(get("/api/users"), token)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/auth/change-pin"), token).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON).content("{\"currentPin\":\"481920\",\"newPin\":\"733914\"}"))
                .andExpect(status().isNoContent());

        // The till they are standing at stays signed in, and now has full admin rights.
        mvc.perform(as(get("/api/users"), token)).andExpect(status().isOk());
    }

    @Test
    void wrongPinIsRejectedWithoutSayingWhichPartWasWrong() throws Exception {
        mvc.perform(login("admin", "000000")).andExpect(status().isUnauthorized());
        mvc.perform(login("nobody", "481920")).andExpect(status().isUnauthorized());
    }

    @Test
    void loggingOutRevokesThatTokenOnly() throws Exception {
        String first = TestAccounts.tokenFor(auth, "asha", UserRole.CASHIER);
        String second = auth.signIn("asha", "883914").token();

        mvc.perform(as(post("/api/auth/logout"), first).header(LocalApiGuard.CLIENT_HEADER, "test"))
                .andExpect(status().isNoContent());

        mvc.perform(as(get("/api/auth/me"), first)).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/auth/me"), second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("asha"));
    }

    @Test
    void rolesDecideWhatEachPersonMayReach() throws Exception {
        String cashier = TestAccounts.tokenFor(auth, "ravi", UserRole.CASHIER);
        String auditor = TestAccounts.tokenFor(auth, "meera", UserRole.AUDITOR);

        // A cashier cannot see or create staff accounts.
        mvc.perform(as(get("/api/users"), cashier)).andExpect(status().isForbidden());
        // An auditor reads, but cannot create.
        mvc.perform(as(get("/api/users"), auditor)).andExpect(status().isOk());
        mvc.perform(as(post("/api/users"), auditor).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"x1\",\"displayName\":\"X\",\"role\":\"CASHIER\",\"pin\":\"551234\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesStaffAndTheNewAccountMustPickItsOwnPin() throws Exception {
        String admin = TestAccounts.tokenFor(auth, "owner", UserRole.ADMIN);

        mvc.perform(as(post("/api/users"), admin).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"kiran\",\"displayName\":\"Kiran\",\"role\":\"MANAGER\",\"pin\":\"640281\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mustChangePin").value(true));

        mvc.perform(login("kiran", "640281")).andExpect(status().isOk());
    }

    @Test
    void anEasilyGuessedPinIsRefused() throws Exception {
        String admin = TestAccounts.tokenFor(auth, "owner2", UserRole.ADMIN);

        mvc.perform(as(post("/api/users"), admin).header(LocalApiGuard.CLIENT_HEADER, "test")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"weak\",\"displayName\":\"W\",\"role\":\"CASHIER\",\"pin\":\"1234\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theLastAdminCannotBeDemoted() {
        long admins = users.countByRoleAndActiveTrue(UserRole.ADMIN);
        assertThat(admins).isPositive();

        String onlyAdminId = users.findByUsernameIgnoreCase("admin").orElseThrow().getId().toString();
        if (admins == 1) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                    () -> auth.updateUser(java.util.UUID.fromString(onlyAdminId), "Administrator", UserRole.CASHIER, true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only administrator");
        }
    }

    @Test
    void thisMachineRegistersItselfWithItsFloor() {
        assertThat(terminals.findByCode("T1")).hasValueSatisfying(terminal -> {
            assertThat(terminal.getName()).isEqualTo("Counter 2");
            assertThat(terminal.getSection()).isEqualTo("1st floor");
            assertThat(terminal.getLastSeenAt()).isNotNull();
        });
    }

    private static MockHttpServletRequestBuilder login(String username, String pin) {
        return post("/api/auth/login").header(LocalApiGuard.CLIENT_HEADER, "test")
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"pin\":\"%s\"}".formatted(username, pin));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
