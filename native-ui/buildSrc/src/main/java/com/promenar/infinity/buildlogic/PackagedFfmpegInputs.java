package com.promenar.infinity.buildlogic;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 内置精简 FFmpeg/x264 源码与校验摘要读取桥接。 */
public final class PackagedFfmpegInputs {
    private final Properties properties;

    private PackagedFfmpegInputs(Properties properties) {
        this.properties = properties;
    }

    public static PackagedFfmpegInputs load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new PackagedFfmpegInputs(properties);
    }

    public String getProperty(String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ffmpeg-android.properties 缺少字段：" + name);
        }
        return value.trim();
    }
}
