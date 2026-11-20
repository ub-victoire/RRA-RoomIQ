package rw.rra.roomiq.booking.disposal.archive;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryArchiveStore implements ArchiveStore {
    private final Map<String, List<ArchiveObject>> batches = new ConcurrentHashMap<>();

    @Override
    public void store(ArchiveManifest manifest, List<ArchiveObject> objects) {
        if (!manifest.verify(objects)) {
            throw new IllegalArgumentException("Archive objects do not match manifest");
        }
        batches.putIfAbsent(manifest.batchSha256(), List.copyOf(objects));
    }

    @Override
    public List<ArchiveObject> read(ArchiveManifest manifest) {
        List<ArchiveObject> objects = batches.get(manifest.batchSha256());
        if (objects == null) {
            throw new IllegalArgumentException("Archive batch not found");
        }
        if (!manifest.verify(objects)) {
            throw new IllegalStateException("Archive batch integrity check failed");
        }
        return objects;
    }
}
