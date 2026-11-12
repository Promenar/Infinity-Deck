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

/** 只打包 Find N5 选型所用的三份权重和词表。 */
@CacheableTask
public abstract class PrepareRecordingAssets extends DefaultTask {
    private static final String SOURCE = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/";
    private static final Map<String, String> FILES = Map.of(
        "decoder-epoch-99-avg-1.onnx", "89be509a83175261695bdef5fd1c7b9ab1129a663d1284e7ba9f8507b21e0906",
        "encoder-epoch-99-avg-1.int8.onnx", "db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb",
        "joiner-epoch-99-avg-1.int8.onnx", "bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9",
        "tokens.txt", "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"
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
                throw new IOException("Zipformer 资产缺失或摘要不符：" + entry.getKey());
            }
        }
    }
}
