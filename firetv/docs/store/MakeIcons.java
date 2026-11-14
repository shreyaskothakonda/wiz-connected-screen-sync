// Renders the store icons from ../../app/src/main/res/drawable/ic_bulb.xml on the app's dark palette.
// Run with the project JDK (no extra dependencies): see make-icons.sh.
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.*;
import javax.imageio.ImageIO;

public class MakeIcons {
    static final Color BG = new Color(0x10, 0x14, 0x18);      // wiz_background
    static final Color SURFACE = new Color(0x1a, 0x20, 0x27); // wiz_surface
    static final Color ACCENT = new Color(0xff, 0xb4, 0x54);  // wiz_accent
    static final Color TEXT = new Color(0xee, 0xf2, 0xf6);    // wiz_text
    static final Color MUTED = new Color(0xae, 0xb8, 0xc3);   // wiz_text_secondary

    public static void main(String[] args) throws Exception {
        Path drawable = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);
        String xml = Files.readString(drawable);
        Matcher m = Pattern.compile("android:pathData=\"([^\"]+)\"").matcher(xml);
        if (!m.find()) throw new IllegalStateException("no pathData in " + drawable);
        Path2D bulb = parse(m.group(1)); // 24x24 viewport

        // Square icons: transparent allowed but a solid rounded tile reads better on the white store page.
        writeSquare(bulb, 512, out.resolve("icon-512.png"));
        writeSquare(bulb, 114, out.resolve("icon-114.png"));
        // Fire TV icon: 1280x720, no transparency, subject inside the 882x448 safe area.
        writeWide(bulb, 1280, 720, 882, 448, true, out.resolve("firetv-icon-1280x720.png"));
        // Fire TV background: 1920x1080, no transparency, subject inside the 1214x830 safe area, no title.
        writeWide(bulb, 1920, 1080, 1214, 830, false, out.resolve("firetv-background-1920x1080.png"));
    }

    static void writeSquare(Path2D bulb, int size, Path file) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = gfx(img);
        double r = size * 0.22;
        g.setColor(BG);
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, r, r));
        drawBulb(g, bulb, size * 0.17, size * 0.14, size * 0.66);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    static void writeWide(Path2D bulb, int w, int h, int safeW, int safeH, boolean title, Path file) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = gfx(img);
        g.setPaint(new GradientPaint(0, 0, SURFACE, 0, h, BG));
        g.fillRect(0, 0, w, h);
        // Faint colour band hinting at the "light follows the picture" idea, kept out of the safe area.
        Color[] band = { new Color(0x3b, 0x5b, 0xdb), new Color(0x9b, 0x59, 0xb6), ACCENT, new Color(0x2e, 0xcc, 0x71) };
        int bandH = (int) (h * 0.012);
        for (int i = 0; i < band.length; i++) {
            Color c = band[i];
            g.setPaint(new GradientPaint(w * i / (float) band.length, 0, alpha(c, 110),
                    w * (i + 1) / (float) band.length, 0, alpha(band[(i + 1) % band.length], 110)));
            g.fillRect(w * i / band.length, h - bandH, w / band.length + 1, bandH);
        }
        double bulbSize = safeH * (title ? 0.62 : 0.75);
        double x0 = (w - safeW) / 2.0, y0 = (h - safeH) / 2.0;
        if (title) {
            drawBulb(g, bulb, x0 + safeW * 0.06, y0 + (safeH - bulbSize) / 2, bulbSize);
            Font f1 = new Font("SansSerif", Font.BOLD, (int) (safeH * 0.19));
            Font f2 = new Font("SansSerif", Font.PLAIN, (int) (safeH * 0.10));
            double tx = x0 + safeW * 0.06 + bulbSize + safeW * 0.04;
            g.setColor(TEXT);
            g.setFont(f1);
            g.drawString("Screen Sync", (float) tx, (float) (y0 + safeH * 0.50));
            g.setColor(MUTED);
            g.setFont(f2);
            g.drawString("for WiZ lights", (float) tx, (float) (y0 + safeH * 0.66));
        } else {
            drawBulb(g, bulb, (w - bulbSize) / 2, (h - bulbSize) / 2, bulbSize);
        }
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    static void drawBulb(Graphics2D g, Path2D bulb, double x, double y, double size) {
        AffineTransform t = new AffineTransform();
        t.translate(x, y);
        t.scale(size / 24.0, size / 24.0);
        Shape s = t.createTransformedShape(bulb);
        // Soft glow behind the bulb.
        Rectangle2D b = s.getBounds2D();
        double cx = b.getCenterX(), cy = b.getCenterY() - size * 0.08, rad = size * 0.6;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) rad,
                new float[] { 0f, 1f }, new Color[] { alpha(ACCENT, 70), alpha(ACCENT, 0) }));
        g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        g.setColor(ACCENT);
        g.fill(s);
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    static Graphics2D gfx(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    /** Minimal SVG path parser: M m L l H h V v C c Z z with implicit command repetition (all ic_bulb needs). */
    static Path2D parse(String d) {
        Path2D.Double p = new Path2D.Double();
        Matcher tok = Pattern.compile("[MmLlHhVvCcZz]|-?\\d*\\.?\\d+(?:e-?\\d+)?").matcher(d);
        List<String> toks = new ArrayList<>();
        while (tok.find()) toks.add(tok.group());
        double cx = 0, cy = 0, sx = 0, sy = 0;
        char cmd = 0;
        for (int i = 0; i < toks.size();) {
            String t = toks.get(i);
            if (Character.isLetter(t.charAt(0))) { cmd = t.charAt(0); i++; if (cmd == 'Z' || cmd == 'z') { p.closePath(); cx = sx; cy = sy; continue; } }
            switch (cmd) {
                case 'M': cx = num(toks, i++); cy = num(toks, i++); p.moveTo(cx, cy); sx = cx; sy = cy; cmd = 'L'; break;
                case 'm': cx += num(toks, i++); cy += num(toks, i++); p.moveTo(cx, cy); sx = cx; sy = cy; cmd = 'l'; break;
                case 'L': cx = num(toks, i++); cy = num(toks, i++); p.lineTo(cx, cy); break;
                case 'l': cx += num(toks, i++); cy += num(toks, i++); p.lineTo(cx, cy); break;
                case 'H': cx = num(toks, i++); p.lineTo(cx, cy); break;
                case 'h': cx += num(toks, i++); p.lineTo(cx, cy); break;
                case 'V': cy = num(toks, i++); p.lineTo(cx, cy); break;
                case 'v': cy += num(toks, i++); p.lineTo(cx, cy); break;
                case 'C': { double x1 = num(toks, i++), y1 = num(toks, i++), x2 = num(toks, i++), y2 = num(toks, i++); cx = num(toks, i++); cy = num(toks, i++); p.curveTo(x1, y1, x2, y2, cx, cy); break; }
                case 'c': { double x1 = cx + num(toks, i++), y1 = cy + num(toks, i++), x2 = cx + num(toks, i++), y2 = cy + num(toks, i++); cx += num(toks, i++); cy += num(toks, i++); p.curveTo(x1, y1, x2, y2, cx, cy); break; }
                default: throw new IllegalStateException("unsupported path command " + cmd);
            }
        }
        return p;
    }

    static double num(List<String> toks, int i) { return Double.parseDouble(toks.get(i)); }
}
