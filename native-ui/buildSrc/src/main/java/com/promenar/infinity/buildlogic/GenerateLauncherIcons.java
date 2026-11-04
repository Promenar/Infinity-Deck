package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 仅使用 Gradle 与 JDK，把点阵无穷 SVG 母版转成各变体的应用图标与通知图标。
 *
 * 母版是 scripts/branding/infinity_icon.py 生成的受限 SVG 子集：根级说明、径向渐变定义、
 * background / foreground 分组和其中的直接路径。路径填充为纯色或径向渐变，可带不透明度与描边。
 * 彩色图标的前景改用发光点阵位图（scripts/branding/GlowIcon.java 按密度生成），SVG 母版只提供背景。
 */
public abstract class GenerateLauncherIcons extends DefaultTask {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String AAPT = "http://schemas.android.com/aapt";
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    private static final Set<String> RESERVED = Set.of("ic_launcher", "ic_launcher_round",
            "ic_launcher_foreground", "ic_launcher_background", "ic_launcher_monochrome", "ic_notification",
            "infinity_launcher_foreground", "infinity_launcher_background");
    private static final Set<String> PATH_ATTRIBUTES = Set.of("d", "fill", "fill-opacity", "stroke", "stroke-width",
            "stroke-linejoin");
    private static final String COLOR = "#[0-9A-F]{6}";
    private static final String NUMBER = "-?[0-9]+(\\.[0-9]+)?";

    /** 彩色母版：108 × 108，含 background 与 foreground 两个分组。 */
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMasterSvg();

    /** 彩色前景位图目录：mdpi…xxxhdpi 五个 108dp 画布 PNG，由 scripts/branding/GlowIcon.java 生成。 */
    @org.gradle.api.tasks.InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getForegroundRasters();

    /** 主题图标母版：108 × 108，仅 foreground 分组，纯色不透明。 */
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMonochromeSvg();

    /** 通知图标母版：24 × 24，仅 foreground 分组，纯色不透明。 */
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getNotificationSvg();

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceRoots();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    public GenerateLauncherIcons() {
        setGroup("build setup");
        setDescription("从点阵无穷母版生成自适应、单色主题与通知栏图标。");
        // 每次检查来源目录，避免新增覆盖被增量缓存掩盖。
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void generate() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        // 资源文件用内部 DTD 实体声明品牌名，校验覆盖时允许文件内声明，仍禁止外部实体与外部 DTD。
        DocumentBuilderFactory resourceFactory = DocumentBuilderFactory.newInstance();
        resourceFactory.setNamespaceAware(true);
        resourceFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        resourceFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        resourceFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        resourceFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        resourceFactory.setXIncludeAware(false);
        for (File source : getSourceRoots()) {
            if (!source.exists()) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(source.toPath())) {
                files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            }
            for (Path file : files) validateSource(resourceFactory, file);
        }

        Master master = Master.parse(factory, getMasterSvg().get().getAsFile(), 108, Set.of("background", "foreground"), false);
        Master monochrome = Master.parse(factory, getMonochromeSvg().get().getAsFile(), 108, Set.of("foreground"), true);
        Master notification = Master.parse(factory, getNotificationSvg().get().getAsFile(), 24, Set.of("foreground"), true);

        Map<String, String> files = new LinkedHashMap<>();
        files.put("drawable/ic_launcher_background.xml", master.vector("background"));
        Map<String, byte[]> binaries = new LinkedHashMap<>();
        File rasters = getForegroundRasters().get().getAsFile();
        for (Map.Entry<String, Integer> density : Map.of("mdpi", 108, "hdpi", 162, "xhdpi", 216, "xxhdpi", 324, "xxxhdpi", 432).entrySet()) {
            File png = new File(rasters, density.getKey() + ".png");
            require(png.isFile(), "缺少前景位图 " + png.getName() + "，请运行 java scripts/branding/GlowIcon.java");
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png);
            require(image != null && image.getWidth() == density.getValue() && image.getHeight() == density.getValue()
                    && image.getColorModel().hasAlpha(), "前景位图 " + png.getName() + " 必须是 " + density.getValue() + " 像素见方的透明 PNG");
            binaries.put("drawable-" + density.getKey() + "/ic_launcher_foreground.png", Files.readAllBytes(png.toPath()));
        }
        files.put("drawable/ic_launcher_monochrome.xml", monochrome.vector("foreground"));
        files.put("drawable/ic_notification.xml", notification.vector("foreground"));
        for (String name : List.of("ic_launcher", "ic_launcher_round")) {
            for (int api : List.of(26, 33)) {
                files.put("mipmap-anydpi-v" + api + "/" + name + ".xml", HEADER +
                        "<adaptive-icon xmlns:android=\"" + ANDROID + "\">\n" +
                        "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n" +
                        "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n" +
                        (api >= 33 ? "    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n" : "") +
                        "</adaptive-icon>\n");
            }
        }
        Path output = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(output);
        try (Stream<Path> walk = Files.walk(output)) {
            for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String relative = output.relativize(file).toString().replace(File.separatorChar, '/');
                if (!files.containsKey(relative) && !binaries.containsKey(relative)) Files.delete(file);
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = output.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, byte[]> file : binaries.entrySet()) {
            Path target = output.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
    }

    /** 已校验的母版：分组名到路径列表，以及按 ID 索引的径向渐变。 */
    private static final class Master {
        final int size;
        final Map<String, List<Element>> groups = new LinkedHashMap<>();
        final Map<String, Element> gradients = new LinkedHashMap<>();

        private Master(int size) {
            this.size = size;
        }

        static Master parse(DocumentBuilderFactory factory, File file, int size, Set<String> groupIds, boolean solid) throws Exception {
            String name = "母版 " + file.getName();
            Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();
            require("svg".equals(root.getTagName()) && SVG.equals(root.getNamespaceURI()) &&
                    ("0 0 " + size + " " + size).equals(root.getAttribute("viewBox")), name + " 必须使用 " + size + " × " + size + " SVG 画布");
            attributes(root, Set.of("xmlns", "width", "height", "viewBox"));
            Master master = new Master(size);
            for (Element child : children(root)) {
                switch (child.getTagName()) {
                    case "title", "desc" -> {
                        require(children(child).isEmpty(), name + " 的说明不得嵌套元素");
                        attributes(child, Set.of());
                    }
                    case "defs" -> {
                        require(!solid, name + " 必须是纯色剪影，不允许渐变");
                        attributes(child, Set.of());
                        for (Element gradient : children(child)) master.addGradient(name, gradient);
                    }
                    case "g" -> {
                        attributes(child, Set.of("id"));
                        String id = child.getAttribute("id");
                        require(groupIds.contains(id) && !master.groups.containsKey(id), name + " 的分组只能是 " + groupIds + " 且各出现一次");
                        List<Element> paths = children(child);
                        require(!paths.isEmpty(), name + " 的 " + id + " 分组不能为空");
                        for (Element path : paths) master.checkPath(name, path, solid);
                        master.groups.put(id, paths);
                    }
                    default -> throw new IllegalArgumentException(name + " 仅允许根级说明、渐变定义与图层分组，禁止嵌套视口、位图或变换");
                }
            }
            require(master.groups.keySet().equals(groupIds), name + " 必须包含分组 " + groupIds);
            return master;
        }

        private void addGradient(String name, Element gradient) {
            require("radialGradient".equals(gradient.getTagName()), name + " 仅允许径向渐变");
            attributes(gradient, Set.of("id", "cx", "cy", "r", "gradientUnits"));
            require("userSpaceOnUse".equals(gradient.getAttribute("gradientUnits")), name + " 的渐变必须使用画布坐标");
            for (String attr : List.of("cx", "cy", "r")) require(gradient.getAttribute(attr).matches(NUMBER), name + " 的渐变坐标必须为数字");
            List<Element> stops = children(gradient);
            require(stops.size() >= 2, name + " 的渐变至少需要两个色标");
            for (Element stop : stops) {
                require("stop".equals(stop.getTagName()) && children(stop).isEmpty(), name + " 的渐变只能包含色标");
                attributes(stop, Set.of("offset", "stop-color", "stop-opacity"));
                require(stop.getAttribute("offset").matches(NUMBER) && stop.getAttribute("stop-color").matches(COLOR) &&
                        stop.getAttribute("stop-opacity").matches(NUMBER), name + " 的色标格式不正确");
            }
            require(gradients.put(gradient.getAttribute("id"), gradient) == null, name + " 的渐变 ID 重复");
        }

        private void checkPath(String name, Element path, boolean solid) {
            require("path".equals(path.getTagName()) && SVG.equals(path.getNamespaceURI()) && children(path).isEmpty(),
                    name + " 的分组只能包含直接路径，禁止嵌套视口");
            attributes(path, PATH_ATTRIBUTES);
            require(path.getAttribute("d").matches("[MmLlHhVvCcQqAaZz0-9 .,\\-]+"), name + " 的路径数据只能使用基本绘图命令");
            String fill = path.getAttribute("fill");
            String stroke = path.getAttribute("stroke");
            if (solid) {
                require(!path.hasAttribute("fill-opacity") && (fill.equals("none") || fill.matches(COLOR)) &&
                        (stroke.isEmpty() || stroke.matches(COLOR)) && !(fill.equals("none") && stroke.isEmpty()),
                        name + " 必须是纯色不透明剪影");
            } else {
                require(fill.matches(COLOR) || (fill.startsWith("url(#") && fill.endsWith(")") &&
                        gradients.containsKey(fill.substring(5, fill.length() - 1))), name + " 的填充只能是纯色或已定义的径向渐变");
                require(stroke.isEmpty(), name + " 的彩色图层不使用描边");
            }
            require(!path.hasAttribute("fill-opacity") || path.getAttribute("fill-opacity").matches(NUMBER), name + " 的不透明度必须为数字");
            require(stroke.isEmpty() == !path.hasAttribute("stroke-width"), name + " 的描边必须同时给出颜色与宽度");
        }

        String vector(String group) {
            boolean gradient = groups.get(group).stream().anyMatch(path -> path.getAttribute("fill").startsWith("url("));
            StringBuilder xml = new StringBuilder(HEADER).append("<vector xmlns:android=\"").append(ANDROID).append('"');
            if (gradient) xml.append(" xmlns:aapt=\"").append(AAPT).append('"');
            xml.append(" android:width=\"").append(size).append("dp\" android:height=\"").append(size)
                    .append("dp\" android:viewportWidth=\"").append(size).append("\" android:viewportHeight=\"").append(size).append("\">\n");
            for (Element path : groups.get(group)) {
                String fill = path.getAttribute("fill");
                xml.append("    <path android:pathData=\"").append(path.getAttribute("d")).append('"');
                if (fill.matches(COLOR)) xml.append(" android:fillColor=\"").append(fill).append('"');
                if (path.hasAttribute("fill-opacity")) xml.append(" android:fillAlpha=\"").append(path.getAttribute("fill-opacity")).append('"');
                if (path.hasAttribute("stroke")) {
                    xml.append(" android:strokeColor=\"").append(path.getAttribute("stroke")).append("\" android:strokeWidth=\"")
                            .append(path.getAttribute("stroke-width")).append('"');
                    if (path.hasAttribute("stroke-linejoin")) xml.append(" android:strokeLineJoin=\"").append(path.getAttribute("stroke-linejoin")).append('"');
                }
                if (!fill.startsWith("url(")) {
                    xml.append(" />\n");
                    continue;
                }
                Element def = gradients.get(fill.substring(5, fill.length() - 1));
                xml.append(">\n        <aapt:attr name=\"android:fillColor\">\n            <gradient android:type=\"radial\" android:centerX=\"")
                        .append(def.getAttribute("cx")).append("\" android:centerY=\"").append(def.getAttribute("cy"))
                        .append("\" android:gradientRadius=\"").append(def.getAttribute("r")).append("\">\n");
                for (Element stop : children(def)) {
                    int alpha = (int) Math.round(Math.max(0, Math.min(1, Double.parseDouble(stop.getAttribute("stop-opacity")))) * 255);
                    xml.append("                <item android:offset=\"").append(stop.getAttribute("offset")).append("\" android:color=\"")
                            .append(String.format(Locale.ROOT, "#%02X", alpha)).append(stop.getAttribute("stop-color").substring(1)).append("\" />\n");
                }
                xml.append("            </gradient>\n        </aapt:attr>\n    </path>\n");
            }
            return xml.append("</vector>\n").toString();
        }
    }

    private static void validateSource(DocumentBuilderFactory factory, Path file) throws Exception {
        String name = file.getFileName().toString();
        require(!(name.startsWith("ic_launcher") || name.startsWith("ic_notification.") || name.equals("launcher_icon_colors.xml")),
                "图标资源必须由母版生成，请移除来源目录中的覆盖：" + name);
        if (name.endsWith(".xml") && file.getParent().getFileName().toString().startsWith("values")) {
            Element values = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            for (Element value : children(values)) require(!RESERVED.contains(value.getAttribute("name")),
                    "图标资源必须由母版生成，请移除 values 中的覆盖：" + name);
        }
        if (!name.equals("AndroidManifest.xml")) return;
        Element manifest = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        for (String tag : List.of("application", "activity", "activity-alias")) {
            var nodes = manifest.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                for (String attr : List.of("icon", "roundIcon")) {
                    String expected = "@mipmap/" + (attr.equals("icon") ? "ic_launcher" : "ic_launcher_round");
                    require(!element.hasAttributeNS(ANDROID, attr) || element.getAttributeNS(ANDROID, attr).equals(expected),
                            "Manifest 图标引用必须统一：" + tag + " 的 " + attr + " 应为 " + expected);
                }
            }
        }
    }

    private static void attributes(Element element, Set<String> allowed) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            require(allowed.contains(element.getAttributes().item(i).getNodeName()), "母版仅允许无变换、无位图的路径与径向渐变");
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) result.add((Element) child);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
