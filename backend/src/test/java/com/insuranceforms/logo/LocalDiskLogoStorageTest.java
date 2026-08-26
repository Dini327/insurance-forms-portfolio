package com.insuranceforms.logo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskLogoStorageTest {

    private LocalDiskLogoStorage storage;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        storage = new LocalDiskLogoStorage(tempDir.toString());
    }

    @Test
    void storesAndResolvesBytes() throws IOException {
        byte[] original = {1, 2, 3, 4, 5};

        LogoRef ref = storage.store(original);

        assertThat(ref.id()).isNotNull();
        assertThat(ref.url()).isEqualTo("/logos/" + ref.id());
        try (InputStream in = storage.resolve(ref.id())) {
            assertThat(in.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void deleteRemovesTheFile() {
        LogoRef ref = storage.store(new byte[]{9, 9, 9});

        storage.delete(ref.id());

        assertThatThrownBy(() -> storage.resolve(ref.id())).isInstanceOf(LogoNotFoundException.class);
    }

    @Test
    void resolvingAnUnknownIdThrows() {
        assertThatThrownBy(() -> storage.resolve(UUID.randomUUID())).isInstanceOf(LogoNotFoundException.class);
    }

    // resolve/delete take a UUID, not a String, specifically so path traversal is
    // structurally impossible: this is the layer that turns an id into a file path
    // (storageDir.resolve(id + ".png")), so the defense belongs here, even though the
    // public URL that supplies the id isn't built until Task 14. UUID.toString() can
    // only ever produce the canonical 8-4-4-4-12 hex format -- it can never contain
    // "/" or "..". The actual boundary this protects is String-to-UUID parsing
    // (whatever future controller does `UUID.fromString(pathVariable)`): proving that
    // parsing itself rejects a traversal attempt is what proves resolve/delete can
    // never be reached with one.
    @Test
    void malformedIdentifierCannotBeParsedIntoAUuidToAttemptPathTraversal() {
        assertThatThrownBy(() -> UUID.fromString("../../../application"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
