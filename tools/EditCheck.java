import com.swfmanager.core.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Round-trip tests for SwfEditor / AssetImport: every edit must produce a file that parses again
 * with exactly the expected assets.
 *
 * usage: EditCheck file.swf [sample.mp3]
 */
public class EditCheck {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static Asset find(SwfDocument d, int id) {
        for (Asset a : d.assets) if (a.id == id) return a;
        return null;
    }

    static byte[] png(int w, int h, int argb) throws Exception {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, argb);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", o);
        return o.toByteArray();
    }

    static int[] pixels(int w, int h, int argb) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, argb);
        return px;
    }

    public static void main(String[] args) throws Exception {
        byte[] raw = Files.readAllBytes(new File(args[0]).toPath());
        SwfDocument d0 = SwfDocument.load(raw);
        int n0 = d0.assets.size();
        System.out.println("start: " + d0.headerSummary());

        // --- add an image with a name -------------------------------------------------------
        int id = SwfEditor.nextId(d0);
        int[] px = pixels(6, 4, 0xFF336699);
        px[0] = 0x80FF0000; // half-transparent red
        px[1] = 0x00000000;
        byte[] r1 = SwfEditor.add(d0, AssetImport.lossless2(id, 6, 4, px), "My_Image");
        SwfDocument d1 = SwfDocument.load(r1);
        Asset a1 = find(d1, id);
        check(d1.assets.size() == n0 + 1 && a1 != null, "add image: asset count +1, id " + id);
        check(a1 != null && a1.type == AssetType.IMAGE && "My_Image".equals(a1.name), "add image: type and name");
        ImageData im = d1.image(id);
        check(im != null && im.width == 6 && im.height == 4, "add image: decodes as 6x4");
        check(im != null && im.argb[2] == 0xFF336699, "add image: opaque pixel exact");
        check(im != null && (im.argb[0] >>> 24) == 0x80 && Math.abs(((im.argb[0] >> 16) & 0xFF) - 255) <= 3, "add image: semi-transparent pixel ~exact");
        check(d1.file.frameCount == d0.file.frameCount, "add image: header frame count kept");
        check(d1.stageWidthTwips() == d0.stageWidthTwips(), "add image: stage size kept");

        // --- add a JPEG (embedded untouched) --------------------------------------------------
        BufferedImage bi = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jo = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpg", jo);
        byte[] jpg = jo.toByteArray();
        check(AssetImport.detect(jpg) == AssetImport.Kind.JPEG && AssetImport.isBaselineJpeg(jpg), "jpeg detected as baseline");
        int id2 = SwfEditor.nextId(d1);
        SwfDocument d2 = SwfDocument.load(SwfEditor.add(d1, AssetImport.jpeg(id2, jpg), null));
        check(find(d2, id2) != null && d2.image(id2) != null && d2.image(id2).width == 8, "add jpeg: decodes as 8x8");

        // --- add binary data -----------------------------------------------------------------
        int id3 = SwfEditor.nextId(d2);
        byte[] blob = "hello binary".getBytes("UTF-8");
        SwfDocument d3 = SwfDocument.load(SwfEditor.add(d2, AssetImport.binary(id3, blob), "Blob"));
        Asset ab = find(d3, id3);
        check(ab != null && ab.type == AssetType.BINARY && "Blob".equals(ab.name), "add binary: type and name");
        check(d3.file.version >= 9, "add binary: version bumped to >= 9 (is " + d3.file.version + ")");

        // --- add WAV --------------------------------------------------------------------------
        int rate = 22050, nsamp = 2205;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < nsamp; i++) {
            int v = (int) (Math.sin(i / 10.0) * 8000);
            pcm.write(v & 0xFF);
            pcm.write((v >> 8) & 0xFF);
        }
        byte[] wav = SoundDecoder.wav(pcm.toByteArray(), rate, 1, 16);
        check(AssetImport.detect(wav) == AssetImport.Kind.WAV, "wav detected");
        int id4 = SwfEditor.nextId(d3);
        SwfDocument d4 = SwfDocument.load(SwfEditor.add(d3, AssetImport.wav(id4, wav), "Beep"));
        SoundData sd = d4.sound(id4);
        check(sd != null && sd.sampleRate == 22050 && sd.sampleCount == nsamp && sd.playable != null, "add wav: decodes (" + (sd == null ? "null" : sd.formatName() + " " + sd.sampleRate) + ")");
        check(sd != null && java.util.Arrays.equals(sd.payload, pcm.toByteArray()), "add wav: samples identical");

        // --- add MP3 --------------------------------------------------------------------------
        if (args.length > 1) {
            byte[] mp3 = Files.readAllBytes(new File(args[1]).toPath());
            check(AssetImport.detect(mp3) == AssetImport.Kind.MP3, "mp3 detected");
            int id5 = SwfEditor.nextId(d4);
            SwfEditor.NewTag t = AssetImport.mp3(id5, mp3);
            SwfDocument d5 = SwfDocument.load(SwfEditor.add(d4, t, null));
            SoundData ms = d5.sound(id5);
            check(ms != null && ms.format == SoundData.FMT_MP3 && ms.playable != null && ms.sampleCount > 0, "add mp3: decodes (" + t.info + ")");
            check(ms != null && ms.playable.length > 0 && (ms.playable[0] & 0xFF) == 0xFF, "add mp3: payload starts at a frame header");
            d4 = d5;
        }

        // --- replace --------------------------------------------------------------------------
        Asset target = find(d4, id);
        SwfDocument r = SwfDocument.load(SwfEditor.replace(d4, target, AssetImport.lossless2(id, 3, 3, pixels(3, 3, 0xFFFFFFFF))));
        check(r.assets.size() == d4.assets.size() && r.image(id) != null && r.image(id).width == 3, "replace image: same count, new 3x3 pixels");
        check("My_Image".equals(find(r, id).name), "replace image: name preserved");
        boolean threw = false;
        try {
            SwfEditor.replace(d4, find(d4, id3), AssetImport.lossless2(id3, 3, 3, pixels(3, 3, 0)));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "replace binary with image is refused");

        // --- rename ---------------------------------------------------------------------------
        SwfDocument rn = SwfDocument.load(SwfEditor.rename(d4, find(d4, id), "Renamed"));
        check("Renamed".equals(find(rn, id).name), "rename: new name shown");
        rn = SwfDocument.load(SwfEditor.rename(rn, find(rn, id), ""));
        check(find(rn, id).name.isEmpty(), "rename: cleared");
        threw = false;
        try {
            SwfEditor.rename(d4, find(d4, id), "Blob");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "rename to a name already in use is refused");

        // --- delete ---------------------------------------------------------------------------
        SwfDocument del = SwfDocument.load(SwfEditor.delete(d4, find(d4, id3)));
        check(find(del, id3) == null && del.assets.size() == d4.assets.size() - 1, "delete binary: gone, others kept");
        check(del.assets.size() > 0 && del.mainTimeline().frameCount() == d4.mainTimeline().frameCount(), "delete: main timeline intact");

        // Delete every shape/image/sprite of the ORIGINAL file one at a time: file must always re-parse
        // and lose exactly that asset, and any placements of it.
        int checked = 0;
        for (Asset a : d0.assets) {
            if (a.id < 0 || checked > 60) continue;
            int placements = SwfEditor.placementCount(d0, a.id);
            SwfDocument x = SwfDocument.load(SwfEditor.delete(d0, a));
            boolean gone = find(x, a.id) == null && x.assets.size() == n0 - 1;
            boolean placed = SwfEditor.placementCount(x, a.id) == 0;
            check(gone && placed, "delete " + a.title() + " (placed " + placements + "x)");
            checked++;
        }

        // Undo-style: original bytes untouched by all edits
        check(java.util.Arrays.equals(raw, Files.readAllBytes(new File(args[0]).toPath())), "source file untouched");
        check(SwfDocument.load(d0.file.data).assets.size() == n0, "original document still parses to the same asset count");

        // --- compression round-trip -----------------------------------------------------------
        byte[] fws = SwfEditor.add(d0, AssetImport.binary(SwfEditor.nextId(d0), blob), null);
        byte[] cws = SwfEditor.finish(fws, true);
        SwfDocument c = SwfDocument.load(cws);
        check(cws[0] == 'C' && c.assets.size() == n0 + 1, "finish(compress): CWS file re-opens with the new asset");
        byte[] plain = SwfEditor.finish(fws, false);
        check(plain[0] == 'F' && SwfDocument.load(plain).assets.size() == n0 + 1, "finish(plain): FWS file re-opens");
        // Declared length must equal the real decompressed length
        long declared = (c.file.data[4] & 0xFFL) | ((c.file.data[5] & 0xFFL) << 8) | ((c.file.data[6] & 0xFFL) << 16) | ((c.file.data[7] & 0xFFL) << 24);
        check(declared == c.file.data.length, "header length field matches decompressed size");

        // --- bad inputs -----------------------------------------------------------------------
        String[] bad = {"mp3-garbage", "wav-garbage"};
        threw = false;
        try { AssetImport.mp3(1, new byte[2000]); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "garbage is not accepted as mp3");
        threw = false;
        try { AssetImport.wav(1, "RIFFxxxxWAVEjunkjunkjunk".getBytes()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "garbage is not accepted as wav");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
