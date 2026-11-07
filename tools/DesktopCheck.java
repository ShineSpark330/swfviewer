import com.swfmanager.core.*;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Desktop harness for the core module: loads an SWF, prints the asset index and writes PNG
 * renders, decoded images and playable audio to an output directory.
 *
 * usage: DesktopCheck file.swf outDir
 */
public class DesktopCheck {

    /** Java2D implementation of Surface (twips in, pixels out via the Graphics2D transform). */
    static final class J2d implements Surface {
        final Graphics2D g;
        final SwfDocument doc;
        final ArrayDeque<Shape> clips = new ArrayDeque<>();
        final ArrayDeque<Shape> prevClips = new ArrayDeque<>();

        J2d(Graphics2D g, SwfDocument doc) {
            this.g = g;
            this.doc = doc;
        }

        static AffineTransform at(Mat m) {
            return new AffineTransform(m.a, m.b, m.c, m.d, m.tx, m.ty);
        }

        static Path2D path(ShapeData.PathData p) {
            Path2D.Float out = new Path2D.Float(Path2D.WIND_EVEN_ODD);
            int pi = 0;
            for (int i = 0; i < p.nCmds; i++) {
                switch (p.cmds[i]) {
                    case ShapeData.PathData.MOVE:
                        out.moveTo(p.pts[pi], p.pts[pi + 1]);
                        pi += 2;
                        break;
                    case ShapeData.PathData.LINE:
                        out.lineTo(p.pts[pi], p.pts[pi + 1]);
                        pi += 2;
                        break;
                    case ShapeData.PathData.QUAD:
                        out.quadTo(p.pts[pi], p.pts[pi + 1], p.pts[pi + 2], p.pts[pi + 3]);
                        pi += 4;
                        break;
                    default:
                        out.closePath();
                }
            }
            return out;
        }

        static Color color(int argb, CXForm cx) {
            if (cx != null) argb = cx.apply(argb);
            return new Color(argb, true);
        }

        static BufferedImage toImage(ImageData d) throws Exception {
            if (d.argb != null) {
                BufferedImage bi = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB);
                bi.setRGB(0, 0, d.width, d.height, d.argb, 0, d.width);
                return bi;
            }
            BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(d.encoded));
            BufferedImage bi = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < bi.getHeight(); y++) {
                for (int x = 0; x < bi.getWidth(); x++) {
                    int rgb = src.getRGB(x, y) & 0xFFFFFF;
                    int a = d.alpha != null ? d.alpha[y * bi.getWidth() + x] & 0xFF : 255;
                    bi.setRGB(x, y, (a << 24) | rgb);
                }
            }
            return bi;
        }

        @Override
        public void drawShape(ShapeData s, Mat m, CXForm cx) {
            AffineTransform saved = g.getTransform();
            try {
                g.transform(at(m));
                for (ShapeData.Fill f : s.fills) fill(f, cx);
                for (ShapeData.Line l : s.lines) {
                    g.setColor(color(l.argb, cx));
                    float w = Math.max(l.width, 20f); // hairlines get 1px
                    g.setStroke(new BasicStroke(w,
                            l.capStyle == 0 ? BasicStroke.CAP_ROUND : l.capStyle == 1 ? BasicStroke.CAP_BUTT : BasicStroke.CAP_SQUARE,
                            l.joinStyle == 0 ? BasicStroke.JOIN_ROUND : l.joinStyle == 1 ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER));
                    g.draw(path(l.path));
                }
            } finally {
                g.setTransform(saved);
            }
        }

        void fill(ShapeData.Fill f, CXForm cx) {
            Path2D p = path(f.path);
            if (f.kind == ShapeData.Fill.SOLID) {
                g.setColor(color(f.argb, cx));
                g.fill(p);
                return;
            }
            AffineTransform saved = g.getTransform();
            try {
                AffineTransform fm = at(f.matrix);
                // Paint in style space: transform the graphics, fill the inverse-transformed path.
                Shape inv = fm.createInverse().createTransformedShape(p);
                g.transform(fm);
                if (f.kind == ShapeData.Fill.BITMAP) {
                    ImageData img = doc.image(f.bitmapId);
                    if (img == null) return;
                    BufferedImage bi = toImage(img);
                    g.setPaint(new TexturePaint(bi, new Rectangle2D.Float(0, 0, bi.getWidth(), bi.getHeight())));
                    g.fill(inv);
                    return;
                }
                float[] fr = f.ratios.clone();
                for (int i = 1; i < fr.length; i++) if (fr[i] <= fr[i - 1]) fr[i] = fr[i - 1] + 0.0001f;
                if (fr[fr.length - 1] > 1f) fr[fr.length - 1] = 1f;
                Color[] cols = new Color[f.colors.length];
                for (int i = 0; i < cols.length; i++) cols[i] = color(f.colors[i], cx);
                if (cols.length < 2) {
                    g.setColor(cols.length == 1 ? cols[0] : Color.GRAY);
                } else if (f.kind == ShapeData.Fill.LINEAR) {
                    g.setPaint(new LinearGradientPaint(new Point2D.Float(-16384, 0), new Point2D.Float(16384, 0),
                            fr, cols, MultipleGradientPaint.CycleMethod.NO_CYCLE));
                } else {
                    g.setPaint(new RadialGradientPaint(new Point2D.Float(0, 0), 16384f, fr, cols));
                }
                g.fill(inv);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                g.setTransform(saved);
            }
        }

        @Override
        public void pushClip(List<ShapeData> shapes, List<Mat> mats) {
            prevClips.push(g.getClip() == null ? new Rectangle2D.Float(-1e7f, -1e7f, 2e7f, 2e7f) : g.getClip());
            java.awt.geom.Area union = new java.awt.geom.Area();
            for (int i = 0; i < shapes.size(); i++) {
                Path2D p = new Path2D.Float(Path2D.WIND_EVEN_ODD);
                for (ShapeData.Fill f : shapes.get(i).fills) p.append(path(f.path), false);
                AffineTransform t = new AffineTransform(g.getTransform());
                t.concatenate(at(mats.get(i)));
                // Clip is stored in device space.
                union.add(new java.awt.geom.Area(t.createTransformedShape(p)));
            }
            AffineTransform saved = g.getTransform();
            g.setTransform(new AffineTransform());
            g.clip(union);
            g.setTransform(saved);
        }

        @Override
        public void popClip() {
            Shape prev = prevClips.pop();
            AffineTransform saved = g.getTransform();
            g.setTransform(new AffineTransform());
            g.setClip(prev);
            g.setTransform(saved);
        }
    }

    static BufferedImage canvas(int w, int h, int bg) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(bg, true));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return bi;
    }

    static void renderMain(SwfDocument doc, int frame, File out) throws Exception {
        int w = Math.max(1, doc.stageWidthTwips() / 20), h = Math.max(1, doc.stageHeightTwips() / 20);
        BufferedImage bi = canvas(w, h, doc.backgroundColor);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(1 / 20.0, 1 / 20.0);
        new FrameRenderer(doc, new J2d(g, doc)).renderMain(frame, Mat.IDENTITY);
        g.dispose();
        ImageIO.write(bi, "png", out);
    }

    static void renderCharacter(SwfDocument doc, Asset a, int tick, File out) throws Exception {
        float[] b = doc.characterBounds(a.id);
        if (b == null) return;
        int w = Math.max(1, (int) Math.ceil((b[2] - b[0]) / 20f)) + 8;
        int h = Math.max(1, (int) Math.ceil((b[3] - b[1]) / 20f)) + 8;
        BufferedImage bi = canvas(w, h, 0xFFF4F4F4);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(4, 4);
        g.scale(1 / 20.0, 1 / 20.0);
        g.translate(-b[0], -b[1]);
        new FrameRenderer(doc, new J2d(g, doc)).renderCharacter(a.id, tick, Mat.IDENTITY);
        g.dispose();
        ImageIO.write(bi, "png", out);
    }

    public static void main(String[] args) throws Exception {
        byte[] raw = Files.readAllBytes(new File(args[0]).toPath());
        File outDir = new File(args[1]);
        outDir.mkdirs();
        SwfDocument doc = SwfDocument.load(raw);
        System.out.println(doc.headerSummary());
        System.out.println("background: #" + Integer.toHexString(doc.backgroundColor & 0xFFFFFF)
                + "  documentClass: " + doc.documentClass);
        Map<AssetType, List<Asset>> groups = doc.grouped();
        for (Map.Entry<AssetType, List<Asset>> e : groups.entrySet()) {
            System.out.println("\n== " + e.getKey().label + " (" + e.getValue().size() + ")");
            for (Asset a : e.getValue()) System.out.println("  " + a.title() + "  |  " + a.subtitle());
        }
        System.out.println("\nRaw tags:");
        for (String s : doc.rawTagLines()) System.out.println("  " + s);

        int problems = 0;
        for (Asset a : doc.assets) {
            String base = a.type.name().toLowerCase() + "_" + a.id;
            switch (a.type) {
                case IMAGE: {
                    ImageData d = doc.image(a.id);
                    if (d == null) {
                        System.out.println("FAIL image " + a.id);
                        problems++;
                        break;
                    }
                    BufferedImage bi = J2d.toImage(d);
                    ImageIO.write(bi, "png", new File(outDir, base + ".png"));
                    System.out.println("image " + a.id + ": " + d.width + "x" + d.height + " " + d.kind
                            + " alpha=" + d.hasAlpha);
                    break;
                }
                case SHAPE: {
                    if (doc.shape(a.id) == null) {
                        System.out.println("FAIL shape " + a.id);
                        problems++;
                        break;
                    }
                    renderCharacter(doc, a, 0, new File(outDir, base + ".png"));
                    break;
                }
                case SPRITE: {
                    renderCharacter(doc, a, 0, new File(outDir, base + "_f0.png"));
                    renderCharacter(doc, a, 3, new File(outDir, base + "_f3.png"));
                    break;
                }
                case SOUND: {
                    SoundData s = doc.sound(a.id);
                    if (s == null || s.playable == null) {
                        System.out.println("sound " + a.id + ": unsupported / failed");
                        problems++;
                        break;
                    }
                    Files.write(new File(outDir, base + "." + s.extension).toPath(), s.playable);
                    System.out.println("sound " + a.id + ": " + s.formatName() + " -> " + s.extension
                            + " " + s.playable.length + " B");
                    break;
                }
                default:
                    break;
            }
        }
        for (int f = 0; f < doc.mainTimeline().frameCount(); f++) {
            renderMain(doc, f, new File(outDir, "main_f" + f + ".png"));
        }
        System.out.println("\nproblems: " + problems);
    }
}
