package com.masspos.backup;

import com.masspos.auth.RequiresRole;
import com.masspos.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Taking a copy of the shop's database, and putting an older one back. Admin only, as it should be. */
@RestController
@RequestMapping("/api/settings/database")
public class BackupController {

    private final DatabaseBackups backups;

    public BackupController(DatabaseBackups backups) {
        this.backups = backups;
    }

    /**
     * @param folder       where the files are, so they can be copied to a pen drive
     * @param restoreFrom  the backup waiting to be put back on the next start, if any
     */
    public record DatabaseView(List<DatabaseBackups.BackupFile> backups, String folder, String restoreFrom) {
    }

    public record RestoreRequest(@NotBlank @Size(max = 200) String file) {
    }

    @GetMapping
    @RequiresRole({UserRole.ADMIN})
    public DatabaseView get() {
        return view();
    }

    @PostMapping("/backup")
    @RequiresRole({UserRole.ADMIN})
    public DatabaseView backUpNow() {
        backups.backUpNow();
        return view();
    }

    /**
     * Lines an old copy up. Nothing changes until the till is restarted, and today's data is
     * copied first, so this can always be undone.
     */
    @PostMapping("/restore")
    @RequiresRole({UserRole.ADMIN})
    public DatabaseView restore(@Valid @RequestBody RestoreRequest request) {
        backups.stageRestore(request.file());
        return view();
    }

    @DeleteMapping("/restore")
    @RequiresRole({UserRole.ADMIN})
    public DatabaseView cancelRestore() {
        backups.cancelRestore();
        return view();
    }

    private DatabaseView view() {
        return new DatabaseView(backups.list(), backups.folder().toAbsolutePath().toString(),
                backups.pendingRestoreSource().orElse(null));
    }
}
