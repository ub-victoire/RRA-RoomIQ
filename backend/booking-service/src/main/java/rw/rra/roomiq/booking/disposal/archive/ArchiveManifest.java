package rw.rra.roomiq.booking.disposal.archive;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record ArchiveManifest(int version, List<ObjectDigest> objects, String batchSha256) {
    private static final byte[] DOMAIN = "roomiq.archive.manifest\0".getBytes(StandardCharsets.US_ASCII);

    public ArchiveManifest {
        if (version <= 0 || objects == null || objects.isEmpty()) {
            throw new IllegalArgumentException("Manifest requires a positive version and nonempty object list");
        }
        objects = List.copyOf(objects);
        String previousId = null;
        for (ObjectDigest object : objects) {
            if (previousId != null && previousId.compareTo(object.objectId()) >= 0) {
                throw new IllegalArgumentException("Manifest object IDs must be unique and sorted");
            }
            previousId = object.objectId();
        }
        if (!batchDigest(version, objects).equals(batchSha256)) {
            throw new IllegalArgumentException("Manifest batch digest is invalid");
        }
    }

    public static ArchiveManifest create(int version, List<ArchiveObject> objects) {
        if (version <= 0 || objects == null || objects.isEmpty()) {
            throw new IllegalArgumentException("Manifest requires a positive version and nonempty object list");
        }
        var ids = new HashSet<String>();
        var digests = new ArrayList<ObjectDigest>(objects.size());
        for (ArchiveObject object : objects) {
            Objects.requireNonNull(object, "archive object");
            if (!ids.add(object.objectId())) {
                throw new IllegalArgumentException("Archive object IDs must be unique");
            }
            digests.add(new ObjectDigest(object.objectId(), hex(digest().digest(object.content()))));
        }
        digests.sort(Comparator.comparing(ObjectDigest::objectId));
        return new ArchiveManifest(version, digests, batchDigest(version, digests));
    }

    public boolean verify(List<ArchiveObject> objects) {
        if (objects == null || objects.size() != this.objects.size()) {
            return false;
        }
        var ids = new HashSet<String>();
        for (ArchiveObject object : objects) {
            if (object == null || !ids.add(object.objectId())) {
                return false;
            }
        }
        return equals(create(version, objects));
    }

    private static String batchDigest(int version, List<ObjectDigest> objects) {
        MessageDigest digest = digest();
        digest.update(DOMAIN);
        updateInt(digest, version);
        updateInt(digest, objects.size());
        for (ObjectDigest object : objects) {
            byte[] id = object.objectId().getBytes(StandardCharsets.UTF_8);
            updateInt(digest, id.length);
            digest.update(id);
            digest.update(HexFormat.of().parseHex(object.sha256()));
        }
        return hex(digest.digest());
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    public record ObjectDigest(String objectId, String sha256) {
        public ObjectDigest {
            ArchiveObject.requireValidId(objectId);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Object digest must be lowercase SHA-256");
            }
        }
    }
}
