import com.swfmanager.core.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

/**
 * Robustness check: loads truncated and randomly corrupted copies of an SWF and exercises every
 * lazy API the app calls. Only a clean SwfException from load() is acceptable; anything else
 * (or any exception from the lazy accessors) is reported.
 *
 * usage: Fuzz file.swf [iterations]
 */
public class Fuzz {
    static int bad = 0, loadFail = 0, ok = 0;

    static void exercise(byte[] data, String label) {
        SwfDocument doc;
        try {
            doc = SwfDocument.load(data);
        } catch (SwfException e) {
            loadFail++;
            return;
        } catch (Throwable t) {
            bad++;
            System.out.println("LOAD threw " + t + " for " + label);
            return;
        }
        try {
            doc.headerSummary();
            doc.rawTagLines();
            doc.mainTimeline();
            FrameRenderer.BoundsSurface bs = new FrameRenderer.BoundsSurface();
            new FrameRenderer(doc, bs).renderMain(0, Mat.IDENTITY);
            for (Asset a : doc.assets) {
                a.title();
                a.subtitle();
                doc.details(a);
                switch (a.type) {
                    case IMAGE: doc.image(a.id); break;
                    case SHAPE: doc.shape(a.id); doc.characterBounds(a.id); break;
                    case SPRITE: doc.spriteTimeline(a.id); doc.characterBounds(a.id); break;
                    case SOUND: doc.sound(a.id); break;
                    default: break;
                }
            }
            ok++;
        } catch (Throwable t) {
            bad++;
            StackTraceElement[] st = t.getStackTrace();
            System.out.println("API threw " + t + " at " + (st.length > 0 ? st[0] : "?") + " for " + label);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] orig = Files.readAllBytes(new File(args[0]).toPath());
        int iters = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        Random rnd = new Random(12345);
        // truncations
        for (int len = 0; len < orig.length; len += Math.max(1, orig.length / 400)) {
            byte[] t = java.util.Arrays.copyOf(orig, len);
            exercise(t, "truncate@" + len);
        }
        // random byte flips (keep the 8-byte header mostly intact so we get past load)
        for (int i = 0; i < iters; i++) {
            byte[] c = orig.clone();
            int flips = 1 + rnd.nextInt(8);
            for (int k = 0; k < flips; k++) {
                int pos = 8 + rnd.nextInt(c.length - 8);
                c[pos] = (byte) rnd.nextInt(256);
            }
            exercise(c, "flip#" + i);
        }
        System.out.println("ok=" + ok + " cleanLoadFail=" + loadFail + " BAD=" + bad);
        if (bad > 0) System.exit(1);
    }
}
