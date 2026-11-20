package rw.rra.roomiq.booking.disposal.archive;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record ArchiveObject(String objectId, byte[] content) {
    public ArchiveObject {
        requireValidId(objectId);
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    static void requireValidId(String objectId) {
        if (objectId == null || objectId.isBlank()
                || !StandardCharsets.UTF_8.newEncoder().canEncode(objectId)) {
            throw new IllegalArgumentException("Archive object ID must be nonblank valid Unicode");
        }
    }
}
