package com.insuranceforms.logo;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogoImageProcessorTest {

    private final LogoImageProcessor processor = new LogoImageProcessor();

    private byte[] renderSampleJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private byte[] renderSampleBmp(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return out.toByteArray();
    }

    private int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ---- format validation: decode-based, not extension/Content-Type ----

    @Test
    void rejectsBytesThatAreNotADecodableImage() {
        byte[] garbage = "not-an-image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> processor.process(garbage))
                .isInstanceOf(InvalidLogoException.class)
                .hasMessageContaining("not a decodable image");
    }

    @Test
    void rejectsSvgBecauseImageIOHasNoSvgDecoder() {
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<rect width=\"10\" height=\"10\"/></svg>").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> processor.process(svg)).isInstanceOf(InvalidLogoException.class);
    }

    // BMP is a format ImageIO CAN decode by default -- proving the allowlist rejects it
    // (with a message distinct from "not a decodable image") shows validation is "is
    // this an allowed format", not just "did ImageIO manage to decode something".
    @Test
    void rejectsFormatsThatDecodeButArentOnTheAllowlist() throws IOException {
        byte[] bmp = renderSampleBmp(50, 50);

        assertThatThrownBy(() -> processor.process(bmp))
                .isInstanceOf(InvalidLogoException.class)
                .hasMessageContaining("Unsupported format");
    }

    // The JDK has no WebP decoder at all, so a naive implementation would let a WebP
    // upload fall all the way through to "File is not a decodable image" -- technically
    // true, but dishonest: the real reason is "this format isn't allowed", the same
    // reason a BMP is rejected above. Detecting WebP by its RIFF....WEBP magic bytes
    // before ImageIO ever touches it is what makes the message honest instead of
    // accidentally generic.
    @Test
    void rejectsWebpWithTheSameHonestMessageAsOtherDisallowedFormatsNotAGenericDecodeFailure() {
        byte[] webp = craftWebpMagicBytes();

        assertThatThrownBy(() -> processor.process(webp))
                .isInstanceOf(InvalidLogoException.class)
                .hasMessageContaining("Unsupported format");
    }

    private byte[] craftWebpMagicBytes() {
        // RIFF header (bytes 0-3) + 4-byte size field (unused by our sniff) + "WEBP"
        // (bytes 8-11). Doesn't need to be a structurally complete WebP file -- this
        // check runs, and rejects, before ImageIO would ever attempt to decode it.
        byte[] bytes = new byte[16];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[8] = 'W';
        bytes[9] = 'E';
        bytes[10] = 'B';
        bytes[11] = 'P';
        return bytes;
    }

    // Explicit, dedicated proof that a *valid* JPEG passes the format allowlist -- not
    // just incidentally covered by the resize/EXIF tests below, which assert other
    // things and would only fail loudly on a JPEG rejection, not point at *why*. This
    // is what would have caught a case-sensitivity bug in the allowlist comparison:
    // ImageIO's format-name casing isn't guaranteed uniform across reader
    // implementations (PNG and JPEG readers don't necessarily agree), so a check that
    // happens to work for one can silently break for the other.
    @Test
    void acceptsAValidJpegThroughTheFormatAllowlist() throws IOException {
        byte[] jpeg = renderSampleJpeg(64, 64);

        byte[] processed = processor.process(jpeg);

        assertThat(processed).isNotNull();
    }

    // ---- transparency ----

    // A logo is exactly the kind of image that commonly has a transparent
    // background. Resizing into an RGB-only (no alpha channel) buffer composites
    // transparent source pixels against that buffer's black default fill --
    // producing a valid, correctly-sized PNG that passes every other test here while
    // silently rendering as a solid black box for a real user. Checking the actual
    // alpha value of the decoded output pixel is what catches that; checking file
    // size or dimensions would not.
    @Test
    void preservesTransparencyInsteadOfCompositingItToOpaqueBlack() throws IOException {
        byte[] transparentPng = renderFullyTransparentPng(10, 10);

        byte[] processed = processor.process(transparentPng);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed));
        assertThat(result.getColorModel().hasAlpha()).isTrue();
        int argb = result.getRGB(0, 0);
        int alpha = (argb >>> 24) & 0xFF;
        assertThat(alpha).isEqualTo(0);
    }

    private byte[] renderFullyTransparentPng(int width, int height) throws IOException {
        // A freshly-created TYPE_INT_ARGB BufferedImage starts as all-zero pixels --
        // (0,0,0,0), i.e. fully transparent -- with no explicit pixel writes needed.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ---- size and dimension limits ----

    @Test
    void rejectsFilesOverTwoMegabytesBeforeDecoding() {
        byte[] oversized = new byte[3 * 1024 * 1024];

        assertThatThrownBy(() -> processor.process(oversized)).isInstanceOf(InvalidLogoException.class);
    }

    // The byte cap alone is not enough: a tiny, well-formed PNG header can declare huge
    // pixel dimensions, and a naive ImageIO.read() would allocate the full decoded bitmap
    // (40000x40000x3 bytes here is ~4.5GB) before this code ever gets to look at the
    // result. This crafts only a PNG signature + IHDR (real CRC32) + IEND -- no IDAT, no
    // actual pixel data -- specifically so ImageReader.getWidth/getHeight can read the
    // claimed dimensions from the header without a full decode ever being attempted.
    @Test
    void rejectsClaimedDimensionsAboveTheMaximumBeforeAllocatingPixelData() throws IOException {
        byte[] bombHeader = craftPngHeaderClaiming(40000, 40000);
        assertThat(bombHeader.length).isLessThan(2 * 1024 * 1024);

        // Message checked specifically, not just the exception type: without this, the
        // test would still pass (for the wrong reason) if the crafted header were
        // rejected earlier as merely "not decodable" instead of genuinely being parsed
        // and rejected for its dimensions.
        assertThatThrownBy(() -> processor.process(bombHeader))
                .isInstanceOf(InvalidLogoException.class)
                .hasMessageContaining("dimensions exceed");
    }

    private byte[] craftPngHeaderClaiming(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        ByteArrayOutputStream ihdrData = new ByteArrayOutputStream();
        writeInt(ihdrData, width);
        writeInt(ihdrData, height);
        ihdrData.write(8); // bit depth
        ihdrData.write(2); // color type: truecolor
        ihdrData.write(0); // compression method
        ihdrData.write(0); // filter method
        ihdrData.write(0); // interlace method
        writeChunk(out, "IHDR", ihdrData.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    // ---- resizing ----

    @Test
    void resizesToAtMost512OnTheLongEdgePreservingAspectRatio() throws IOException {
        byte[] wideJpeg = renderSampleJpeg(1000, 500);

        byte[] processed = processor.process(wideJpeg);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed));
        assertThat(result.getWidth()).isEqualTo(512);
        assertThat(result.getHeight()).isEqualTo(256);
    }

    @Test
    void leavesSmallImagesAtOriginalSize() throws IOException {
        byte[] smallJpeg = renderSampleJpeg(100, 80);

        byte[] processed = processor.process(smallJpeg);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed));
        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(80);
    }

    // ---- metadata stripping: proven by reading the actual saved output back ----

    @Test
    void reEncodingStripsRealExifMetadataAndOutputIsPng() throws IOException {
        String secretMarker = "SECRET-CAMERA-MODEL-XYZ123";
        byte[] jpegWithExif = renderJpegWithFakeExif(300, 200, secretMarker);

        // Sanity check on the fixture itself, before processing: if the marker weren't
        // actually present here, its absence afterward would prove nothing.
        assertThat(indexOf(jpegWithExif, secretMarker.getBytes(StandardCharsets.US_ASCII))).isNotEqualTo(-1);
        assertThat(indexOf(jpegWithExif, "Exif".getBytes(StandardCharsets.US_ASCII))).isNotEqualTo(-1);

        byte[] processed = processor.process(jpegWithExif);

        assertThat(indexOf(processed, secretMarker.getBytes(StandardCharsets.US_ASCII))).isEqualTo(-1);
        assertThat(indexOf(processed, "Exif".getBytes(StandardCharsets.US_ASCII))).isEqualTo(-1);

        byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(java.util.Arrays.copyOfRange(processed, 0, 8)).isEqualTo(pngSignature);
    }

    // Embeds a real APP1/EXIF marker segment via ImageIO's own JPEG metadata API (not
    // string concatenation) -- MarkerTag 225 (0xE1) is APP1, and the payload starts with
    // the literal "Exif\0\0" identifier real cameras/phones use.
    private byte[] renderJpegWithFakeExif(int width, int height, String marker) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);

        String formatName = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);
        IIOMetadataNode markerSequence = getOrCreateChild(root, "markerSequence");

        byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        byte[] markerBytes = marker.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[exifHeader.length + markerBytes.length];
        System.arraycopy(exifHeader, 0, payload, 0, exifHeader.length);
        System.arraycopy(markerBytes, 0, payload, exifHeader.length, markerBytes.length);

        IIOMetadataNode app1 = new IIOMetadataNode("unknown");
        app1.setAttribute("MarkerTag", "225");
        app1.setUserObject(payload);
        markerSequence.appendChild(app1);

        metadata.setFromTree(formatName, root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, metadata), param);
        }
        writer.dispose();
        return out.toByteArray();
    }

    private IIOMetadataNode getOrCreateChild(IIOMetadataNode root, String name) {
        var nodes = root.getElementsByTagName(name);
        if (nodes.getLength() > 0) {
            return (IIOMetadataNode) nodes.item(0);
        }
        IIOMetadataNode child = new IIOMetadataNode(name);
        root.appendChild(child);
        return child;
    }
}
