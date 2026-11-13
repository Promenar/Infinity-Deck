package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.inject.Inject;

/** 只打包 X-ASR 480ms 中英标点模型的三份权重和 BPE 词表。 */
@CacheableTask
public abstract class PrepareRecordingAssets extends DefaultTask {
    private static final String SOURCE = "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/";
    private static final Map<String, String> FILES = Map.of(
        "decoder.onnx", "a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257",
        "encoder.int8.onnx", "908596dcc137a73b95be908ca55e88caa1b3dbbe8027c171615f4b0609c5eb1e",
        "joiner.int8.onnx", "aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3",
        "tokens.txt", "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c"
    );

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchive();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Inject protected abstract ArchiveOperations getArchiveOperations();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction public void prepare() throws IOException {
        Path output = getOutputDirectory().get().getAsFile().toPath();
        getFileSystemOperations().sync(spec -> {
            spec.from(getArchiveOperations().tarTree(getArchive().get().getAsFile()));
            spec.include(FILES.keySet().stream().map(name -> SOURCE + name).toList());
            spec.eachFile(file -> file.setPath("asr/" + file.getName()));
            spec.setIncludeEmptyDirs(false);
            spec.into(output);
        });
        for (var entry : FILES.entrySet()) {
            Path file = output.resolve("asr").resolve(entry.getKey());
            if (!Files.isRegularFile(file) || !FetchVerifiedFile.sha256(file).equals(entry.getValue())) {
                throw new IOException("X-ASR 资产缺失或摘要不符：" + entry.getKey());
            }
        }
    }
}
