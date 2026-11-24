package com.promenar.infinity.buildlogic;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class PackagedHostToolsInputs {
    private static final List<String> PACKAGES = List.of(
            "git", "curl", "zstd", "xz", "bzip2", "libarchive", "jq", "sqlite", "rsync", "ripgrep", "fd"
    );

    public record Source(String name, String url, String sha256, String archive) {}

    private PackagedHostToolsInputs() {}

    public static List<Source> load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return PACKAGES.stream().map(name -> {
            String url = required(properties, name + ".url");
            String sha256 = required(properties, name + ".sha256");
            String archive = required(properties, name + ".archive");
            if (!archive.matches("[A-Za-z0-9._-]+\\.(?:tar\\.(?:gz|xz)|zip)") || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("宿主工具源码归档或摘要无效：" + name);
            }
            return new Source(name, url, sha256, archive);
        }).toList();
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("host-tools-android.properties 缺少字段：" + name);
        }
        return value.trim();
    }
}
