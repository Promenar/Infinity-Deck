package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

@CacheableTask
public abstract class BuildPackagedHostTools extends DefaultTask {
    public static final List<String> OUTPUT_LIBRARIES = List.of(
            "libnxgit.so", "libnxgit-remote-https.so", "libnxgitssh.so", "libnxrg.so",
            "libnxfd.so", "libnxjq.so", "libnxsqlite3.so", "libnxzstd.so",
            "libnxbsdtar.so", "libnxrsync.so", "libnxtoolshim.so"
    );

    @InputFiles @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getSourceArchives();

    @InputFiles @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getBuildInputs();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBuildScript();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getReleaseVerifier();

    @Input
    public abstract Property<Integer> getApiLevel();

    @Input
    public abstract Property<String> getRustIdentity();

    @Internal
    public abstract DirectoryProperty getNdkDirectory();

    @Internal
    public abstract DirectoryProperty getSourceDirectory();

    @Internal
    public abstract DirectoryProperty getCacheDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject protected abstract ExecOperations getExecOperations();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    public BuildPackagedHostTools() {
        setGroup("build setup");
        setDescription("交叉编译 Android arm64-v8a 侧载宿主工具并缓存复用。");
    }

    @Input
    public String getNdkRevision() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(getNdkDirectory().file("source.properties").get().getAsFile().toPath())) {
            properties.load(reader);
        }
        return properties.getProperty("Pkg.Revision", "unknown");
    }

    @TaskAction
    public void build() throws IOException {
        String fingerprint = fingerprint();
        Path cacheParent = getCacheDirectory().get().getAsFile().toPath();
        Files.createDirectories(cacheParent);
        Path cachedTools = cacheParent.resolve(fingerprint);
        // 共享缓存跨 worktree 复用。锁覆盖校验、修复和复制，避免并发读取不完整的结果。
        synchronized (BuildPackagedHostTools.class) {
            try (FileChannel channel = FileChannel.open(cacheParent.resolve(fingerprint + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                if (!validCache(cachedTools)) {
                    compile(cacheParent, cachedTools);
                } else {
                    getLogger().lifecycle("复用已验证的宿主工具缓存 {}", fingerprint);
                }

                Path out = getOutputDirectory().get().getAsFile().toPath();
                delete(out);
                Path abiOutput = Files.createDirectories(out.resolve("arm64-v8a"));
                for (String library : OUTPUT_LIBRARIES) {
                    Files.copy(cachedTools.resolve(library), abiOutput.resolve(library), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void compile(Path cacheParent, Path cachedTools) throws IOException {
        Path work = Files.createTempDirectory(cacheParent, ".host-tools-");
        try {
            Path staging = Files.createDirectory(work.resolve("output"));
            File script = getBuildScript().get().getAsFile();
            getExecOperations().exec(spec -> {
                // Cargo 和 rustc 版本探针在同一工作目录解析 rustup 工具链。
                spec.setWorkingDir(script.getParentFile().getParentFile());
                spec.commandLine(
                        "bash", script.getAbsolutePath(),
                        getSourceDirectory().get().getAsFile().getAbsolutePath(),
                        getNdkDirectory().get().getAsFile().getAbsolutePath(),
                        getApiLevel().get().toString(),
                        work.toAbsolutePath().toString(),
                        staging.toAbsolutePath().toString(),
                        getReleaseVerifier().get().getAsFile().getAbsolutePath()
                );
            });
            Files.write(cachedManifest(staging), libraryDigests(staging));
            delete(cachedTools);
            Files.move(staging, cachedTools, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            delete(work);
        }
    }

    private void delete(Path path) {
        getFileSystemOperations().delete(spec -> spec.delete(path.toFile()));
    }

    private static Path cachedManifest(Path directory) {
        return directory.resolve(".complete");
    }

    private static boolean validCache(Path directory) throws IOException {
        if (!Files.isRegularFile(cachedManifest(directory))) return false;
        for (String library : OUTPUT_LIBRARIES) {
            if (!Files.isRegularFile(directory.resolve(library))) return false;
        }
        return Files.readAllLines(cachedManifest(directory)).equals(libraryDigests(directory));
    }

    private static List<String> libraryDigests(Path directory) throws IOException {
        var digests = new ArrayList<String>(OUTPUT_LIBRARIES.size());
        for (String library : OUTPUT_LIBRARIES) {
            digests.add(FetchVerifiedFile.sha256(directory.resolve(library)) + "  " + library);
        }
        return digests;
    }

    private String fingerprint() throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String toolchain = "arm64-v8a\n" + getApiLevel().get() + "\n" + getNdkRevision() + "\n" + getRustIdentity().get();
        digest.update(toolchain.getBytes(StandardCharsets.UTF_8));
        List<File> files = Stream.concat(
                        Stream.concat(getSourceArchives().getFiles().stream(), getBuildInputs().getFiles().stream()),
                        Stream.of(getBuildScript().get().getAsFile(), getReleaseVerifier().get().getAsFile()))
                .sorted(Comparator.comparing(File::getName)).toList();
        for (File file : files) {
            String identity = "\n" + file.getName() + "=" + FetchVerifiedFile.sha256(file.toPath());
            digest.update(identity.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
    }
}
