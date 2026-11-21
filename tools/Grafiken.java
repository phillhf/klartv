import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Erzeugt TV-Banner und Store-Grafiken aus demselben Symbol wie das App-Icon.
 * Aufruf im Projektordner: java -Djava.awt.headless=true tools/Grafiken.java .
 */
public class Grafiken {
    static final Color BG = new Color(0x0B1220), BG2 = new Color(0x15284A), ACCENT = new Color(0x38BDF8),
            TEXT = new Color(0xF1F5F9), SUB = new Color(0x94A3B8);

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        banner(320, 180, new File(root, "app/src/main/res/drawable-xhdpi/tv_banner.png"));
        banner(1280, 720, new File(root, "store/tv-banner-1280x720.png"));
        icon(512, new File(root, "store/icon-512.png"));
        feature(1024, 500, new File(root, "store/feature-1024x500.png"));
    }

    static Graphics2D start(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static void background(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, BG2, w, h, BG));
        g.fillRect(0, 0, w, h);
    }

    /** Das Symbol im 108er-Raster des Adaptive Icons, auf Größe s skaliert. */
    static void symbol(Graphics2D g, double x, double y, double s) {
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.scale(s / 108.0, s / 108.0);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Double(30, 36, 48, 36, 8, 8));
        Path2D t = new Path2D.Double();
        t.moveTo(48, 45);
        t.lineTo(62, 54);
        t.lineTo(48, 63);
        t.closePath();
        g.fill(t);
        g.draw(new Line2D.Double(44, 79, 64, 79));
        g.setTransform(old);
    }

    static void save(BufferedImage img, File f) throws Exception {
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("geschrieben: " + f);
    }

    static void banner(int w, int h, File f) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(img);
        background(g, w, h);
        // Symbol belegt im 108er-Raster x 30..78 (Anteil 0,278..0,722)
        double s = h * 1.0;
        double left = h * 0.1;
        symbol(g, left - 0.278 * s, (h - s) / 2 - h * 0.02, s);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, (int) (h * 0.23)));
        g.drawString("Klar TV", (float) (left + 0.444 * s + h * 0.1), (float) (h * 0.58));
        g.dispose();
        save(img, f);
    }

    static void icon(int size, File f) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(img);
        g.setColor(BG);
        g.fillRect(0, 0, size, size);
        double s = size * 1.5;
        symbol(g, (size - s) / 2, (size - s) / 2 - size * 0.04, s);
        g.dispose();
        save(img, f);
    }

    static void feature(int w, int h, File f) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(img);
        background(g, w, h);
        double s = h * 1.0;
        double left = w * 0.07;
        symbol(g, left - 0.278 * s, (h - s) / 2, s);
        float textX = (float) (left + 0.444 * s + 48);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 96));
        g.drawString("Klar TV", textX, h * 0.5f);
        g.setColor(SUB);
        g.setFont(new Font("SansSerif", Font.PLAIN, 34));
        g.drawString("Dein IPTV-Player. Ohne Werbung.", textX, h * 0.64f);
        g.dispose();
        save(img, f);
    }
}
