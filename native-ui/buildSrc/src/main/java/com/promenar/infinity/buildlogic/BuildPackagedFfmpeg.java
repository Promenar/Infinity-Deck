package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * 为 Android arm64-v8a 交叉编译精简共享库版 FFmpeg + ffprobe（含 8-bit libx264），
 * 产物按输入指纹缓存在 Gradle 用户缓存目录，供主工作树与发行工作树共同复用。
 */
public abstract class BuildPackagedFfmpeg extends DefaultTask {
    public static final List<String> OUTPUT_LIBRARIES = List.of(
            "libnxffmpeg.so",
            "libnxffprobe.so",
            "libavcodec_nxff.so",
            "libavfilter_nxff.so",
            "libavformat_nxff.so",
            "libavutil_nxff.so",
            "libswresample_nxff.so",
            "libswscale_nxff.so"
    );

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFfmpegArchive();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getX264Archive();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBuildScript();

    @Input
    public abstract Property<String> getAbi();

    @Input
    public abstract Property<Integer> getApiLevel();

    @Internal
    public abstract DirectoryProperty getNdkDirectory();

    @Internal
    public abstract DirectoryProperty getCacheDirectory();

    @Input
    public String getNdkRevision() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(getNdkDirectory().get().file("source.properties").getAsFile().toPath())) {
            properties.load(input);
        }
        return properties.getProperty("Pkg.Revision", "unknown");
    }

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject protected abstract ExecOperations getExecOperations();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    public BuildPackagedFfmpeg() {
        setGroup("build setup");
        setDescription("为 Android arm64-v8a 交叉编译精简 FFmpeg/ffprobe 共享库并缓存复用。");
    }

    @TaskAction
    public void build() throws IOException {
        String abi = getAbi().get();
        if (!"arm64-v8a".equals(abi)) {
            throw new GradleException("当前内置 FFmpeg 构建任务仅支持 arm64-v8a，收到：" + abi);
        }
        Path ffmpegArchive = getFfmpegArchive().get().getAsFile().toPath();
        Path x264Archive = getX264Archive().get().getAsFile().toPath();
        Path buildScript = getBuildScript().get().getAsFile().toPath();
        String fingerprint = buildFingerprint(abi, getApiLevel().get(), getNdkRevision(), ffmpegArchive, x264Archive, buildScript);

        Path cacheDir = getCacheDirectory().get().getAsFile().toPath().resolve(fingerprint);
        Path cacheDone = cacheDir.resolve(".complete");
        if (!isValidCache(cacheDir, cacheDone)) {
            Path staging = cacheDir.resolveSibling(fingerprint + ".building-" + ProcessHandle.current().pid());
            getFileSystemOperations().delete(spec -> spec.delete(staging));
            Files.createDirectories(staging);
            File workDir = new File(getTemporaryDir(), "ffmpeg-work");
            getFileSystemOperations().delete(spec -> spec.delete(workDir));
            try {
                getExecOperations().exec(spec -> spec.commandLine(
                        "bash",
                        buildScript.toAbsolutePath().toString(),
                        "--ffmpeg-archive", ffmpegArchive.toAbsolutePath().toString(),
                        "--x264-archive", x264Archive.toAbsolutePath().toString(),
                        "--ndk-dir", getNdkDirectory().get().getAsFile().getAbsolutePath(),
                        "--api", String.valueOf(getApiLevel().get()),
                        "--output-dir", staging.toAbsolutePath().toString(),
                        "--work-dir", workDir.getAbsolutePath()
                ));
                for (String lib : OUTPUT_LIBRARIES) {
                    if (!Files.isRegularFile(staging.resolve(lib))) {
                        throw new GradleException("FFmpeg 构建缺少输出库：" + lib);
                    }
                }
                Files.writeString(staging.resolve(".complete"), fingerprint + "\n", StandardCharsets.UTF_8);
                Files.createDirectories(cacheDir.getParent());
                if (!isValidCache(cacheDir, cacheDone)) {
                    getFileSystemOperations().delete(spec -> spec.delete(cacheDir));
                    Files.move(staging, cacheDir, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    getFileSystemOperations().delete(spec -> spec.delete(staging));
                }
            } finally {
                getFileSystemOperations().delete(spec -> spec.delete(workDir));
                if (Files.exists(staging)) {
                    getFileSystemOperations().delete(spec -> spec.delete(staging));
                }
            }
        }

        Path outRoot = getOutputDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(outRoot));
        Path outAbi = Files.createDirectories(outRoot.resolve(abi));
        for (String lib : OUTPUT_LIBRARIES) {
            Files.copy(cacheDir.resolve(lib), outAbi.resolve(lib), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isValidCache(Path cacheDir, Path doneMarker) {
        if (!Files.isRegularFile(doneMarker)) return false;
        for (String lib : OUTPUT_LIBRARIES) {
            if (!Files.isRegularFile(cacheDir.resolve(lib))) return false;
        }
        return true;
    }

    private static String buildFingerprint(
            String abi,
            int apiLevel,
            String ndkRevision,
            Path ffmpegArchive,
            Path x264Archive,
            Path buildScript
    ) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String summary = String.join("\n",
                "abi=" + abi,
                "api=" + apiLevel,
                "ndk=" + ndkRevision,
                "ffmpeg=" + FetchVerifiedFile.sha256(ffmpegArchive),
                "x264=" + FetchVerifiedFile.sha256(x264Archive),
                "script=" + FetchVerifiedFile.sha256(buildScript)
        );
        return HexFormat.of().formatHex(digest.digest(summary.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
    }
}
