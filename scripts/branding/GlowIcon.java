import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发光点阵无穷图标的确定性栅格处理：只做缩放、居中与合成，不改变母版内容，不计入生成式编辑。
 *
 * 用法（仓库根目录）：java scripts/branding/GlowIcon.java
 * 输入：assets/branding/infinity-glow-master.png（带透明通道的母版）。
 * 输出：
 *   assets/branding/launcher-foreground/<密度>.png  自适应图标前景，108dp 画布，符号落在 66dp 安全圆内；
 *   assets/branding/infinity-glow-icon.png          512 × 512 展示图，深蓝圆角底，取中心 72dp 可见区；
 *   assets/branding/build/glow-preview-circle.png    圆形遮罩预览，只用于人工检查，不入库。
 */
public final class GlowIcon {
    /** 统计符号范围时忽略的低透明度光晕。 */
    private static final int ALPHA_THRESHOLD = 40;
    /** 符号外接圆半径（dp），留出光晕余量，小于 33dp 安全圆半径。 */
    private static final double SYMBOL_RADIUS_DP = 31.0;
    private static final double CANVAS_DP = 108.0;
    private static final double VISIBLE_DP = 72.0;

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        BufferedImage master = toPremultiplied(ImageIO.read(new File(root, "assets/branding/infinity-glow-master.png")));
        Fit fit = Fit.measure(master);

        Map<String, Double> densities = new LinkedHashMap<>();
        densities.put("mdpi", 1.0);
        densities.put("hdpi", 1.5);
        densities.put("xhdpi", 2.0);
        densities.put("xxhdpi", 3.0);
        densities.put("xxxhdpi", 4.0);
        File launcherDir = new File(root, "assets/branding/launcher-foreground");
        launcherDir.mkdirs();
        for (Map.Entry<String, Double> density : densities.entrySet()) {
            int size = (int) Math.round(CANVAS_DP * density.getValue());
            BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
            drawSymbol(canvas, master, fit, size / CANVAS_DP, size / 2.0, size / 2.0);
            ImageIO.write(toStraight(canvas), "png", new File(launcherDir, density.getKey() + ".png"));
        }

        // 展示图：可见区 72dp 映射到 512px，背景沿用自适应图标的深蓝径向渐变。
        int display = 512;
        double pxPerDp = display / VISIBLE_DP;
        BufferedImage tile = new BufferedImage(display, display, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = quality(tile.createGraphics());
        double offset = (CANVAS_DP - VISIBLE_DP) / 2.0;
        RadialGradientPaint background = new RadialGradientPaint(
                new Point2D.Double((54.0 - offset) * pxPerDp, (46.0 - offset) * pxPerDp), (float) (76.0 * pxPerDp),
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0x18204A), new Color(0x0B0F26), new Color(0x04050C)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g.setPaint(background);
        double corner = display * 0.45;
        g.fill(new RoundRectangle2D.Double(0, 0, display, display, corner, corner));
        g.setComposite(AlphaComposite.SrcAtop);
        g.dispose();
        drawSymbol(tile, master, fit, pxPerDp, display / 2.0, display / 2.0);
        ImageIO.write(toStraight(tile), "png", new File(root, "assets/branding/infinity-glow-icon.png"));

        // 圆形遮罩预览：模拟启动器的圆形裁切，检查安全区。
        BufferedImage preview = new BufferedImage(display, display, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D p = quality(preview.createGraphics());
        p.setPaint(background);
        p.fill(new java.awt.geom.Ellipse2D.Double(0, 0, display, display));
        p.dispose();
        drawSymbol(preview, master, fit, pxPerDp, display / 2.0, display / 2.0);
        File previewDir = new File(root, "assets/branding/build");
        previewDir.mkdirs();
        ImageIO.write(toStraight(preview), "png", new File(previewDir, "glow-preview-circle.png"));

        System.out.printf("符号中心 (%.1f, %.1f)，外接半径 %.1fpx，缩放到 %.1fdp%n", fit.centerX, fit.centerY, fit.radius, SYMBOL_RADIUS_DP);
    }

    /** 母版中符号的中心与外接半径（按透明度阈值统计，单位为母版像素）。 */
    private record Fit(double centerX, double centerY, double radius) {
        static Fit measure(BufferedImage image) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) >= ALPHA_THRESHOLD) {
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                }
            }
            if (maxX < 0) throw new IllegalStateException("母版没有可见像素");
            double cx = (minX + maxX + 1) / 2.0, cy = (minY + maxY + 1) / 2.0;
            double radius = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if ((image.getRGB(x, y) >>> 24) >= ALPHA_THRESHOLD) {
                        radius = Math.max(radius, Math.hypot(x + 0.5 - cx, y + 0.5 - cy));
                    }
                }
            }
            return new Fit(cx, cy, radius);
        }
    }

    /** 把符号缩放到 [pxPerDp] 对应的尺寸，中心放在 (targetX, targetY)。逐级减半缩小以保留点阵细节。 */
    private static void drawSymbol(BufferedImage canvas, BufferedImage master, Fit fit, double pxPerDp, double targetX, double targetY) {
        double scale = SYMBOL_RADIUS_DP * pxPerDp / fit.radius;
        BufferedImage source = master;
        double remaining = scale;
        while (remaining < 0.5) {
            int w = Math.max(1, source.getWidth() / 2), h = Math.max(1, source.getHeight() / 2);
            BufferedImage half = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = quality(half.createGraphics());
            g.drawImage(source, 0, 0, w, h, null);
            g.dispose();
            remaining *= (double) source.getWidth() / w;
            source = half;
        }
        double sourceScale = (double) source.getWidth() / master.getWidth();
        double finalScale = scale / sourceScale;
        double w = source.getWidth() * finalScale, h = source.getHeight() * finalScale;
        double x = targetX - fit.centerX * scale, y = targetY - fit.centerY * scale;
        Graphics2D g = quality(canvas.createGraphics());
        g.drawImage(source, (int) Math.round(x), (int) Math.round(y), (int) Math.round(w), (int) Math.round(h), null);
        g.dispose();
    }

    private static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        return g;
    }

    private static BufferedImage toPremultiplied(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toStraight(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }
}
