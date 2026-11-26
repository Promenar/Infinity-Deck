package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 用于侧载变体 的固定源码交叉编译，下载与编译输出不进入源码目录。 */
public abstract class BuildLinuxRuntime extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getProotArchive();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getTallocArchive();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getBuildSources();
    @InputDirectory @PathSensitive(PathSensitivity.NONE)
    public abstract DirectoryProperty getNdkDirectory();
    @Input public abstract Property<String> getProotVersion();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Inject protected abstract ArchiveOperations getArchiveOperations();
    @Inject protected abstract ExecOperations getExecOperations();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void build() throws IOException {
        File work = getTemporaryDir();
        getFileSystemOperations().delete(spec -> spec.delete(work));
        File proot = unpack(getProotArchive().get().getAsFile(), new File(work, "proot"));
        File talloc = unpack(getTallocArchive().get().getAsFile(), new File(work, "talloc"));
        File ndk = getNdkDirectory().get().getAsFile();
        File cmakeBin = new File(ndk.getParentFile().getParentFile(), "cmake/3.22.1/bin");
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        File cmake = new File(cmakeBin, "cmake" + suffix);
        if (!cmake.isFile()) throw new GradleException("缺少项目使用的 SDK CMake 3.22.1");
        File build = new File(work, "cmake");
        getExecOperations().exec(spec -> spec.commandLine(cmake,
                "-S", getBuildSources().get().getAsFile(), "-B", build, "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=" + new File(cmakeBin, "ninja" + suffix),
                "-DCMAKE_TOOLCHAIN_FILE=" + new File(ndk, "build/cmake/android.toolchain.cmake"),
                "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-31",
                "-DPROOT_SOURCE=" + proot, "-DTALLOC_SOURCE=" + talloc,
                "-DPROOT_VERSION=" + getProotVersion().get(), "-DCMAKE_BUILD_TYPE=Release"));
        getExecOperations().exec(spec -> spec.commandLine(cmake, "--build", build, "--parallel", "2"));
        Path out = getOutputDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(out));
        Path abi = Files.createDirectories(out.resolve("arm64-v8a"));
        for (String name : new String[]{"libnxproot.so", "libnxproot-loader.so"}) {
            Files.copy(build.toPath().resolve(name), abi.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File unpack(File archive, File target) {
        getFileSystemOperations().copy(spec -> {
            spec.from(getArchiveOperations().tarTree(getArchiveOperations().gzip(archive)));
            spec.into(target);
        });
        File[] roots = target.listFiles(File::isDirectory);
        if (roots == null || roots.length != 1) throw new GradleException("源码归档根目录异常：" + archive.getName());
        return roots[0];
    }
}
