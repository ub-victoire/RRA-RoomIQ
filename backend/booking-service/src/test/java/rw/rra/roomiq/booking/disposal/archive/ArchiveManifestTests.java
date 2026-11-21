package rw.rra.roomiq.booking.disposal.archive;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveManifestTests {
    private static ArchiveObject object(String id, String content) {
        return new ArchiveObject(id, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void objectDigestMatchesSha256AndBatchChangesWhenAnyObjectChanges() throws NoSuchAlgorithmException {
        var first = object("a", "alpha");
        var second = object("b", "beta");
        var original = ArchiveManifest.create(1, List.of(first, second));
        assertThat(original.objects().getFirst().sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("alpha".getBytes(StandardCharsets.UTF_8))));
        assertThat(ArchiveManifest.create(1, List.of(object("a", "changed"), second)).batchSha256())
                .isNotEqualTo(original.batchSha256());
        assertThat(ArchiveManifest.create(1, List.of(first, object("b", "changed"))).batchSha256())
                .isNotEqualTo(original.batchSha256());
        assertThat(ArchiveManifest.create(1, List.of(object("other", "alpha"), second)).batchSha256())
                .isNotEqualTo(original.batchSha256());
    }

    @Test
    void canonicalOrderingMakesReorderedInputProduceTheSameDigest() {
        var first = object("a", "alpha");
        var second = object("b", "beta");
        var original = ArchiveManifest.create(1, List.of(second, first));
        assertThat(original.objects()).extracting(ArchiveManifest.ObjectDigest::objectId).containsExactly("a", "b");
        assertThat(ArchiveManifest.create(1, List.of(first, second)).batchSha256())
                .isEqualTo(original.batchSha256());
    }

    @Test
    void changingManifestVersionChangesDigest() {
        var objects = List.of(object("a", "alpha"));
        assertThat(ArchiveManifest.create(1, objects).batchSha256())
                .isNotEqualTo(ArchiveManifest.create(2, objects).batchSha256());
    }

    @Test
    void identicalInputsAlwaysProduceIdenticalManifest() {
        var objects = List.of(object("a", "alpha"), object("b", "beta"));
        assertThat(ArchiveManifest.create(1, objects)).isEqualTo(ArchiveManifest.create(1, objects));
        assertThat(ArchiveManifest.create(1, objects).batchSha256())
                .isEqualTo("495fe35621994f079d0404bcda518ecdd31e2284a5c4a5772010caa1a5c495f3");
    }

    @Test
    void duplicateObjectIdsAreRejected() {
        assertThatThrownBy(() -> ArchiveManifest.create(1, List.of(object("a", "one"), object("a", "two"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyBatchIsRejected() {
        assertThatThrownBy(() -> ArchiveManifest.create(1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArchiveManifest.create(0, List.of(object("a", "alpha"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verificationDetectsTamperedMissingAndExtraObjects() {
        var original = List.of(object("a", "alpha"), object("b", "beta"));
        var manifest = ArchiveManifest.create(1, original);
        assertThat(manifest.verify(original)).isTrue();
        assertThat(manifest.verify(List.of(object("a", "tampered"), original.get(1)))).isFalse();
        assertThat(manifest.verify(List.of(original.getFirst()))).isFalse();
        assertThat(manifest.verify(List.of(original.getFirst(), original.get(1), object("c", "extra")))).isFalse();
        assertThat(manifest.verify(List.of(original.getFirst(), original.getFirst()))).isFalse();
    }

    @Test
    void archivePortExposesNoDeleteOperation() {
        assertThat(ArchiveStore.class.getMethods()).extracting(method -> method.getName())
                .containsExactlyInAnyOrderElementsOf(Set.of("store", "read"));
    }

    @Test
    void inMemoryAdapterPreservesContentAndRejectsTamperedWrites() {
        var objects = List.of(object("a", "alpha"), object("b", "beta"));
        var manifest = ArchiveManifest.create(1, objects);
        ArchiveStore store = new InMemoryArchiveStore();
        store.store(manifest, objects);
        assertThat(manifest.verify(store.read(manifest))).isTrue();
        assertThatThrownBy(() -> store.store(manifest, List.of(object("a", "tampered"), objects.get(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(manifest.verify(store.read(manifest))).isTrue();
    }

    @Test
    void byteArraysAndManifestAreDefensiveSnapshots() {
        byte[] content = "alpha".getBytes(StandardCharsets.UTF_8);
        var object = new ArchiveObject("a", content);
        var objects = new java.util.ArrayList<>(List.of(object));
        var manifest = ArchiveManifest.create(1, objects);
        ArchiveStore store = new InMemoryArchiveStore();
        store.store(manifest, objects);
        content[0] = 0;
        object.content()[0] = 0;
        objects.clear();
        assertThat(manifest.verify(store.read(manifest))).isTrue();
        assertThatThrownBy(() -> manifest.objects().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void suppliedManifestMustHaveCanonicalObjectsAndMatchingDigest() {
        var manifest = ArchiveManifest.create(1, List.of(object("a", "alpha"), object("b", "beta")));
        assertThatThrownBy(() -> new ArchiveManifest(1, manifest.objects(), "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveManifest(1,
                List.of(manifest.objects().get(1), manifest.objects().getFirst()), manifest.batchSha256()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveObject("\ud800", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
