package com.masspos.backup;

import com.masspos.billing.InvoiceRepository;
import com.masspos.config.PosProperties;
import com.masspos.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Putting a shop back before anyone has signed in.
 *
 * <p>A till being replaced has its database on a pen drive, and that database holds the staff and
 * their PINs. Asking someone to sign in first and restore afterwards is backwards: the PIN they
 * have is the old machine's, and the new one only knows the factory admin. So this is open
 * without a token - but only while the till is untouched, which means no bill has ever been
 * issued on it and the factory admin has not yet picked its own PIN. After that it refuses, and
 * restoring is an admin job on the Settings screen like everything else.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);
    /** A pen drive full of other things should not be trawled; a backup folder is small. */
    private static final int MAX_CANDIDATES = 50;

    private final DatabaseBackups backups;
    private final BackupProperties offsite;
    private final InvoiceRepository invoices;
    private final UserRepository users;
    private final PosProperties pos;

    public SetupController(DatabaseBackups backups, BackupProperties offsite, InvoiceRepository invoices,
                           UserRepository users, PosProperties pos) {
        this.backups = backups;
        this.offsite = offsite;
        this.invoices = invoices;
        this.users = users;
        this.pos = pos;
    }

    /**
     * @param fresh       true while this till can still be replaced wholesale
     * @param candidates  copies found on this machine, newest first
     * @param restoreFrom what the next start will put back, if anything is already staged
     */
    public record SetupView(boolean fresh, List<Candidate> candidates, String restoreFrom, String lookedIn) {
    }

    public record Candidate(String name, long sizeBytes, String takenAt, String folder) {
    }

    public record RestoreRequest(@NotBlank @Size(max = 300) String file, @Size(max = 500) String folder) {
    }

    @GetMapping
    public SetupView get() {
        return view();
    }

    /** One of the copies this machine already has. */
    @PostMapping("/restore")
    public SetupView restore(@Valid @RequestBody RestoreRequest request) {
        requireFresh();
        Path file = Path.of(request.folder() == null || request.folder().isBlank()
                ? offsite.startStopDir().toString() : request.folder()).resolve(request.file()).normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("There is no backup called " + request.file() + " any more");
        }
        backups.stageRestoreFrom(file, request.file());
        log.warn("Setup: {} is staged and will be put back on the next start", file);
        return view();
    }

    /**
     * A file chosen in the browser, sent as it is. Written next to the database first because a
     * SQLite file cannot be read from memory, then checked and staged like any other.
     */
    @PostMapping("/upload")
    public SetupView upload(@RequestBody byte[] contents,
                            @RequestParam(defaultValue = "chosen-backup.db") String name) {
        requireFresh();
        if (contents.length == 0) {
            throw new IllegalArgumentException("That file is empty");
        }
        try {
            Path landing = Files.createTempFile(Files.createDirectories(pos.dataDir()), "uploaded-", ".db");
            Files.write(landing, contents);
            try {
                backups.stageRestoreFrom(landing, name);
                log.warn("Setup: the uploaded {} is staged and will be put back on the next start", name);
            } finally {
                Files.deleteIfExists(landing);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the file you chose", e);
        }
        return view();
    }

    /**
     * Once a bill exists, the shop's own records are on this till and replacing them wholesale is
     * not a thing to leave open to anybody who can reach the port.
     */
    private void requireFresh() {
        if (!isFresh()) {
            throw new IllegalStateException("This till is already in use. Restore a backup from"
                    + " Settings > Database instead, where it asks who you are first.");
        }
    }

    private boolean isFresh() {
        return invoices.count() == 0
                && users.findAll().stream().allMatch(user -> user.isMustChangePin());
    }

    private SetupView view() {
        return new SetupView(isFresh(), candidates(), backups.pendingRestoreSource().orElse(null),
                offsite.offsiteDir().normalize().toString());
    }

    /** The automatic copies first: on a machine being replaced they are the newest thing there is. */
    private List<Candidate> candidates() {
        List<Candidate> found = new ArrayList<>();
        for (Path folder : List.of(offsite.startStopDir(), backups.folder())) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(file -> file.getFileName().toString().endsWith(".db"))
                        .sorted(Comparator.comparing(DatabaseBackups::modifiedAt).reversed())
                        .limit(MAX_CANDIDATES)
                        .forEach(file -> found.add(describe(file, folder)));
            } catch (IOException unreadable) {
                log.info("Cannot look in {} for backups: {}", folder, unreadable.toString());
            }
        }
        return List.copyOf(found);
    }

    private static Candidate describe(Path file, Path folder) {
        try {
            return new Candidate(file.getFileName().toString(), Files.size(file),
                    Files.getLastModifiedTime(file).toInstant().toString(), folder.toString());
        } catch (IOException e) {
            return new Candidate(file.getFileName().toString(), 0, "", folder.toString());
        }
    }
}