package rw.rra.roomiq.booking.disposal.archive;

import java.util.List;

/**
 * Port for write-once archive batches. Implementations must reject content that does not match the manifest
 * and verify integrity when reading; this interface does not itself provide durable or compliant storage.
 */
public interface ArchiveStore {
    void store(ArchiveManifest manifest, List<ArchiveObject> objects);

    List<ArchiveObject> read(ArchiveManifest manifest);
}
