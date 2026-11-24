package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把内置 Tailscale 节点（app/tailnet 下的 Go 程序）编译为 jniLibs 打包的可执行文件 libnxtailnet.so。
 * App 原生库压缩打包、安装时解压到 nativeLibraryDir，命名为 lib*.so 才能被 execve。
 * 固定 Go 版本（GOTOOLCHAIN 精确指定）、go.sum 只读校验依赖、非 cgo 静态链接（网卡信息由 androidbin 合成）、
 * -trimpath 与空 buildid，同一输入在任意主机上得到相同产物，二进制中不含构建机路径。
 */
public abstract class BuildTailnetBinary extends DefaultTask {
    static final String SKIP_HINT = "可安装 Go 1.21 及以上版本（构建时经 GOTOOLCHAIN 自动使用登记的版本），"
            + "用 -Pinfinity.goExecutable=<go 路径> 指定，或以 -Pinfinity.tailnet=false 跳过（运行时报告内置 Tailscale 不可用）";

    /** Go 源码、go.mod 与 go.sum。 */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** 模块根目录（go.mod 所在目录），只用作工作目录。 */
    @Internal
    public abstract DirectoryProperty getModuleDirectory();

    /** 登记的 Go 版本，例如 1.27.1。 */
    @Input
    public abstract Property<String> getGoVersion();

    @Input
    public abstract Property<String> getAbi();

    /** GOOS：android，或在 Android 内核上运行的静态 linux 程序。 */
    @Input
    public abstract Property<String> getGoOs();

    /** GOARCH，例如 arm64、amd64。 */
    @Input
    public abstract Property<String> getGoArch();

    /** go 命令名或路径；路径因主机而异，不参与增量判断，版本由 GOTOOLCHAIN 固定。 */
    @Internal
    public abstract Property<String> getGoExecutable();

    /** 输出根目录，内含 <abi>/ 子目录，可直接作为 jniLibs 源目录。 */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject protected abstract ExecOperations getExecOperations();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    public BuildTailnetBinary() {
        setGroup("build setup");
        setDescription("用固定 Go 版本把" + componentName() + "编译为 jniLibs 可执行文件 " + executableName() + "。");
    }

    protected String componentName() { return "内置 Tailscale"; }
    protected String executableName() { return "libnxtailnet.so"; }
    protected String skipHint() { return SKIP_HINT; }
    protected List<String> buildTags() { return List.of(); }

    @TaskAction
    public void build() throws Exception {
        File module = getModuleDirectory().get().getAsFile();
        if (!new File(module, "go.sum").isFile()) throw new GradleException(componentName() + "缺少 go.sum：" + module);
        Map<String, String> environment = environment();
        String version = run(module, environment, "env", "GOVERSION").trim();
        String expected = "go" + getGoVersion().get();
        if (!version.equals(expected)) {
            throw new GradleException(componentName() + "需要 " + expected + "，当前 go 报告 " + version + "；" + skipHint());
        }
        File output = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(output));
        File abiDir = new File(output, getAbi().get());
        Files.createDirectories(abiDir.toPath());
        File binary = new File(abiDir, executableName());
        // -R 显式固定 64KB 段地址舍入（PT_LOAD 对齐），兼容 16KB 页设备，不依赖 Go 链接器默认值。
        List<String> args = new ArrayList<>(List.of("build", "-trimpath", "-buildvcs=false",
                "-ldflags=-s -w -buildid= -R 0x10000", "-o", binary.getPath()));
        if (!buildTags().isEmpty()) args.add("-tags=" + String.join(",", buildTags()));
        args.add(".");
        run(module, environment, args.toArray(String[]::new));
        if (!binary.isFile() || binary.length() == 0) throw new GradleException("Go 构建未生成 " + binary);
    }

    private Map<String, String> environment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GOOS", getGoOs().get());
        environment.put("GOARCH", getGoArch().get());
        environment.put("CGO_ENABLED", "0");
        environment.put("GOTOOLCHAIN", "go" + getGoVersion().get());
        // go.sum 之外的依赖变化一律失败；不使用 go.work 与主机上的 GOFLAGS。
        environment.put("GOFLAGS", "-mod=readonly");
        environment.put("GOWORK", "off");
        return environment;
    }

    private String run(File module, Map<String, String> environment, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String go = getGoExecutable().get();
        ExecResult result;
        try {
            result = getExecOperations().exec(spec -> {
                spec.setExecutable(go);
                spec.args((Object[]) args);
                spec.setWorkingDir(module);
                spec.environment(environment);
                spec.setStandardOutput(stdout);
                spec.setErrorOutput(stderr);
                spec.setIgnoreExitValue(true);
            });
        } catch (RuntimeException failure) {
            throw new GradleException("无法运行「" + go + "」构建" + componentName() + "；" + skipHint(), failure);
        }
        if (result.getExitValue() != 0) {
            throw new GradleException("go " + args[0] + " 失败（退出码 " + result.getExitValue() + "）：\n"
                    + stderr.toString(StandardCharsets.UTF_8).trim() + "\n" + skipHint());
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
