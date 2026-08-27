package com.insuranceforms.logo;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

public class LogoImageProcessor {

    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;

    // Per-side cap on the *source* image, checked before any pixel data is decoded --
    // this is what blocks a decompression bomb (a tiny, well-formed file whose header
    // claims huge dimensions). Generous relative to any real agency logo, nowhere near
    // memory-danger territory (4000x4000 RGB is ~48MB decoded).
    private static final int MAX_SOURCE_DIMENSION = 4000;
    private static final long MAX_SOURCE_PIXELS = (long) MAX_SOURCE_DIMENSION * MAX_SOURCE_DIMENSION;

    // Resize target for the *output* -- unrelated to MAX_SOURCE_DIMENSION above.
    private static final int MAX_OUTPUT_DIMENSION = 512;

    // WebP intentionally excluded: the JDK's built-in ImageIO has no WebP decoder, and
    // adding one is a new third-party image-decoding dependency purely for a logo --
    // not worth the added attack surface (image decoders are a classic CVE source). If
    // WebP is ever required, add only the imageio-webp module, not the full
    // TwelveMonkeys suite. All lowercase: ImageIO's format-name casing isn't guaranteed
    // uniform across reader implementations, so the comparison always lowercases first.
    private static final Set<String> ALLOWED_FORMATS = Set.of("png", "jpeg", "jpg");

    public byte[] process(byte[] uploadedBytes) {
        if (uploadedBytes.length > MAX_UPLOAD_BYTES) {
            throw new InvalidLogoException("File exceeds the 2MB upload limit");
        }
        // ImageIO has no WebP reader at all, so without this a WebP upload would fall
        // through to the generic "not a decodable image" branch below -- true, but
        // dishonest about the actual reason. Sniffing the RIFF....WEBP magic bytes
        // first gives it the same "unsupported format" message as any other
        // disallowed-but-recognizable format (e.g. BMP).
        if (looksLikeWebp(uploadedBytes)) {
            throw new InvalidLogoException("Unsupported format — upload a PNG or JPEG file");
        }

        checkFormatAndDimensions(uploadedBytes);

        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(uploadedBytes));
        } catch (IOException e) {
            throw new InvalidLogoException("File is not a decodable image");
        }
        if (decoded == null) {
            throw new InvalidLogoException("File is not a decodable image");
        }

        BufferedImage resized = resize(decoded);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean looksLikeWebp(byte[] bytes) {
        if (bytes.length < 12) {
            return false;
        }
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    // Reads only the image header (format + dimensions) via ImageReader, never
    // triggering a full pixel decode -- that's what lets this reject an oversized or
    // disallowed image before any large allocation happens.
    private void checkFormatAndDimensions(byte[] uploadedBytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(uploadedBytes))) {
            if (iis == null) {
                throw new InvalidLogoException("File is not a decodable image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new InvalidLogoException("File is not a decodable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);

                String formatName = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(formatName)) {
                    throw new InvalidLogoException("Unsupported format — upload a PNG or JPEG file");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION
                        || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new InvalidLogoException("Image dimensions exceed the allowed maximum");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidLogoException("File is not a decodable image");
        }
    }

    private BufferedImage resize(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);

        int targetWidth = width;
        int targetHeight = height;
        if (longEdge > MAX_OUTPUT_DIMENSION) {
            double scale = MAX_OUTPUT_DIMENSION / (double) longEdge;
            targetWidth = (int) Math.round(width * scale);
            targetHeight = (int) Math.round(height * scale);
        }

        // Drawing into a fresh BufferedImage discards any metadata the source carried —
        // ImageIO.read() already returns pixel data only, but this makes the "fresh file,
        // nothing carried over" guarantee explicit rather than incidental.
        //
        // ARGB, not RGB: a logo is exactly the kind of image that commonly has a
        // transparent background (PNG). Drawing a transparent source into an RGB-only
        // (no alpha channel) buffer composites the transparent areas against that
        // buffer's black default fill -- silently turning a transparent logo into one
        // with a solid black box behind it. Preserving the alpha channel keeps
        // transparency end-to-end; an opaque JPEG source is simply marked fully
        // opaque, no harm done.
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return result;
    }
}
