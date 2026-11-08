package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把纯 Python wheel 与 Infinity Deck 自带模块合成为不压缩 zip（libpysite.so），作为标准库之后的第二个
 * PYTHONPATH 条目；安装后位于 nativeLibraryDir，只读，不解压。
 * 启用预编译时每个 .py 旁加入哈希 pyc，源码保留，内置 git 出错时回溯仍能显示源码行；见 {@link PythonBytecode}。
 */
public abstract class PreparePackagedSite extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getWheels();

    /** Infinity Deck 自带的顶层模块（如 git 包装）。 */
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getModuleDirectory();

    /** 不进入 zip 的路径前缀（相对 zip 根），例如上游测试目录。 */
    @Input
    public abstract ListProperty<String> getExcludedPrefixes();

    @Input
    public abstract Property<String> getAbi();

    /** 是否预编译字节码。 */
    @Input
    public abstract Property<Boolean> getCompileBytecode();

    /** 打包 CPython 的 PYC_MAGIC_NUMBER。 */
    @Input
    public abstract Property<Integer> getBytecodeMagic();

    /** 宿主解释器（命令名或路径）；产物只取决于 magic，路径不参与增量判断。 */
    @Internal
    public abstract Property<String> getHostPython();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBytecodeCompiler();

    /** 输出根目录，内含 <abi>/ 子目录，可直接作为 jniLibs 源目录。 */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject protected abstract FileSystemOperations getFileSystemOperations();
    @Inject protected abstract ExecOperations getExecOperations();

    public PreparePackagedSite() {
        setGroup("build setup");
        setDescription("把纯 Python 依赖与 Infinity Deck 模块合成为 jniLibs 打包库 libpysite.so。");
    }

    @TaskAction
    public void prepare() throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        List<File> wheels = new ArrayList<>(getWheels().getFiles());
        wheels.sort((left, right) -> left.getName().compareTo(right.getName()));
        for (File wheel : wheels) readWheel(wheel, files);
        Path modules = getModuleDirectory().get().getAsFile().toPath();
        try (Stream<Path> walk = Files.walk(modules)) {
            for (Path path : walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".py")).toList()) {
                add(files, modules.relativize(path).toString().replace(File.separatorChar, '/'), Files.readAllBytes(path), path.toString());
            }
        }

        if (getCompileBytecode().get()) addBytecode(files);

        File output = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(output));
        Path abiDir = output.toPath().resolve(getAbi().get());
        Files.createDirectories(abiDir);
        try (StoredZip zip = new StoredZip(abiDir.resolve("libpysite.so"))) {
            String previousDirectory = "";
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                // 补齐目录条目，供命名空间包与 importlib.resources 识别。
                String name = file.getKey();
                int slash = name.lastIndexOf('/');
                String directory = slash < 0 ? "" : name.substring(0, slash + 1);
                if (!directory.isEmpty() && !directory.equals(previousDirectory)) {
                    for (String parent : parents(directory)) {
                        if (!previousDirectory.startsWith(parent)) zip.directory(parent);
                    }
                    previousDirectory = directory;
                }
                zip.put(name, file.getValue());
            }
        }
    }

    /** 在临时目录中编译全部 .py，把生成的同目录 pyc 加入 [files]。 */
    private void addBytecode(Map<String, byte[]> files) throws IOException {
        File work = new File(getTemporaryDir(), "bytecode");
        getFileSystemOperations().delete(spec -> spec.delete(work));
        Path root = work.toPath();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (!file.getKey().endsWith(".py")) continue;
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
        }
        getLogger().info(PythonBytecode.compileTree(getExecOperations(), getHostPython().get(),
                getBytecodeCompiler().get().getAsFile(), getBytecodeMagic().get(), root));
        for (String name : new ArrayList<>(files.keySet())) {
            if (!name.endsWith(".py")) continue;
            Path compiled = root.resolve(name + "c");
            if (Files.isRegularFile(compiled)) add(files, name + "c", Files.readAllBytes(compiled), compiled.toString());
        }
        getFileSystemOperations().delete(spec -> spec.delete(work));
    }

    private void readWheel(File wheel, Map<String, byte[]> files) throws IOException {
        List<String> excluded = getExcludedPrefixes().get();
        try (InputStream input = Files.newInputStream(wheel.toPath()); ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new GradleException(wheel.getName() + " 含非法路径：" + name);
                }
                // *.data/ 下的脚本与数据安装到其它位置，打包库中用不到。
                if (name.substring(0, name.indexOf('/') + 1).endsWith(".data/") || name.contains("/__pycache__/")) continue;
                if (excluded.stream().anyMatch(name::startsWith)) continue;
                if (name.endsWith(".so") || name.endsWith(".pyd") || name.endsWith(".dylib")) {
                    throw new GradleException(wheel.getName() + " 含原生库，不是纯 Python 包：" + name);
                }
                add(files, name, zip.readAllBytes(), wheel.getName());
            }
        }
    }

    private static void add(Map<String, byte[]> files, String name, byte[] content, String source) {
        if (files.putIfAbsent(name, content) != null) throw new GradleException("打包 Python 模块重名：" + name + "（" + source + "）");
    }

    /** a/b/c/ → [a/, a/b/, a/b/c/] */
    private static List<String> parents(String directory) {
        List<String> parents = new ArrayList<>();
        for (int index = directory.indexOf('/'); index >= 0; index = directory.indexOf('/', index + 1)) {
            parents.add(directory.substring(0, index + 1));
        }
        return parents;
    }
}
