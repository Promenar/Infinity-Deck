package com.promenar.infinity.screenshot;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * 在独立 JVM 分片内通过 JUnit Platform 启动 Compose 截图测试引擎。
 * 供 scripts/ci/validate-screenshots-parallel.py 多进程并行调度使用。
 */
public final class ScreenshotShardRunner {
    private static final String ENGINE_ID = "preview-screenshot-test-engine";

    private ScreenshotShardRunner() {}

    public static void main(String[] args) {
        int exitCode = 1;
        try {
            exitCode = run(args);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            System.out.flush();
            System.err.flush();
            // Layoutlib / Compose 渲染可能残留非守护线程，完成后显式退出避免分片进程挂起。
            System.exit(exitCode);
        }
    }

    private static int run(String[] args) throws IOException {
        Path classesFile = null;
        for (int index = 0; index < args.length; index++) {
            if ("--classes-file".equals(args[index]) && index + 1 < args.length) {
                classesFile = Path.of(args[++index]);
            }
        }
        if (classesFile == null) {
            System.err.println("缺少必填参数：--classes-file <路径>");
            return 2;
        }
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (String rawLine : Files.readAllLines(classesFile, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int hashIndex = line.indexOf('#');
            if (hashIndex > 0 && hashIndex + 1 < line.length()) {
                selectors.add(DiscoverySelectors.selectMethod(
                    line.substring(0, hashIndex),
                    line.substring(hashIndex + 1)
                ));
            } else {
                selectors.add(DiscoverySelectors.selectClass(line));
            }
        }
        if (selectors.isEmpty()) {
            System.err.println("分片类清单为空：" + classesFile);
            return 2;
        }

        LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request()
            .filters(EngineFilter.includeEngines(ENGINE_ID));
        for (DiscoverySelector selector : selectors) {
            requestBuilder.selectors(selector);
        }
        LauncherDiscoveryRequest request = requestBuilder.build();

        SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, summaryListener);

        TestExecutionSummary summary = summaryListener.getSummary();
        long found = summary.getTestsFoundCount();
        long succeeded = summary.getTestsSucceededCount();
        long failed = summary.getTestsFailedCount();
        long aborted = summary.getTestsAbortedCount();
        long skipped = summary.getTestsSkippedCount();
        long containerFailed = summary.getContainersFailedCount();

        System.out.printf(
            "SHARD_SUMMARY found=%d succeeded=%d failed=%d aborted=%d skipped=%d containerFailed=%d%n",
            found, succeeded, failed, aborted, skipped, containerFailed
        );

        if (!summary.getFailures().isEmpty()) {
            PrintWriter err = new PrintWriter(System.err, true);
            for (TestExecutionSummary.Failure failure : summary.getFailures()) {
                err.printf(
                    "FAILED: %s (%s) -> %s%n",
                    failure.getTestIdentifier().getDisplayName(),
                    failure.getTestIdentifier().getUniqueId(),
                    failure.getException()
                );
            }
        }

        boolean ok = found > 0 && failed == 0 && containerFailed == 0 && aborted == 0 && skipped == 0;
        return ok ? 0 : 1;
    }
}
