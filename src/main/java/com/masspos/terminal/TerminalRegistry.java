package com.masspos.terminal;

import com.masspos.config.PosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Registers this machine in the terminal table on every start, so the shop's master ends up with a
 * list of its counters and the floors they stand on.
 */
@Component
public class TerminalRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TerminalRegistry.class);

    private final TerminalRepository terminals;
    private final PosProperties pos;

    public TerminalRegistry(TerminalRepository terminals, PosProperties pos) {
        this.terminals = terminals;
        this.pos = pos;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        PosProperties.Terminal settings = pos.terminal();
        Terminal terminal = terminals.findByCode(settings.code())
                .orElseGet(() -> terminals.save(new Terminal(settings.code(), settings.name(), settings.section(),
                        settings.type())));
        terminal.describe(settings.name(), settings.section(), settings.type(), Instant.now());
        log.info("This machine is {} \"{}\" ({}) in {}", terminal.getCode(), terminal.getName(), terminal.getType(),
                terminal.getSection() == null || terminal.getSection().isBlank() ? "no section set" : terminal.getSection());
    }
}
