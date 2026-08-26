package com.insuranceforms.logo;

import java.io.InputStream;
import java.util.UUID;

// Every logo reaching this layer is already re-encoded PNG (Task 13's
// LogoImageProcessor strips metadata and re-encodes before storage ever sees
// the bytes) -- there is no other format to preserve, so this interface takes
// no content type. Implementations always write/serve image/png.
public interface LogoStorage {
    LogoRef store(byte[] bytes);
    InputStream resolve(UUID id);
    void delete(UUID id);
}
