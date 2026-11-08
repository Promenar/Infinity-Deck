package com.promenar.infinity.share.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 仅存在于 androidTest APK，避免 Provider 独立进程启动时依赖 Kotlin runtime。 */
public final class ShareFixtureProvider extends ContentProvider {
    public static final String VALID_FILE = "fixture.txt";
    public static final String SECOND_FILE = "fixture-second.txt";
    public static final String BLOCKING_FILE = "fixture-blocking.txt";
    public static final String UTF8_BOUNDARY_FILE = "fixture-utf8-boundary.txt";
    public static final String HTML_FILE = "fixture.html";
    public static final String INVALID_PDF = "fake.pdf";
    public static final String METHOD_RESET_BLOCKING_READ = "resetBlockingRead";
    public static final String METHOD_AWAIT_BLOCKING_READ = "awaitBlockingRead";
    public static final String METHOD_RELEASE_BLOCKING_READ = "releaseBlockingRead";
    public static final String RESULT_READY = "ready";
    public static final byte[] CONTENT = "Infinity Deck share fixture".getBytes(StandardCharsets.UTF_8);
    public static final byte[] UTF8_BOUNDARY_CONTENT = utf8BoundaryContent();
    public static final byte[] HTML_CONTENT = (
            "<!doctype html><html><head><title>本地文档</title>"
                    + "<script>window.__fixture_secret__='脚本密文不应被索引';</script>"
                    + "<link rel='stylesheet' href='https://remote.example/hidden.css'></head>"
                    + "<body><h1>中文标题</h1>"
                    + "<p>中文正文一，包含 emoji 🙂，用于验证离线文档引用提取。</p>"
                    + "<p>中文正文二，继续提供足够长度以验证切块和重叠边界。</p>"
                    + "<img src='https://remote.example/hidden-image.png' alt='远程图片'>"
                    + "<table><tr><td>甲</td><td>乙</td></tr></table></body></html>"
    ).getBytes(StandardCharsets.UTF_8);
    private static volatile CountDownLatch blockingReadStarted = new CountDownLatch(1);
    private static volatile CountDownLatch releaseBlockingRead = new CountDownLatch(1);
    private static final byte[] FAKE_PDF = "not a pdf".getBytes(StandardCharsets.UTF_8);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (VALID_FILE.equals(name) || SECOND_FILE.equals(name) || BLOCKING_FILE.equals(name) ||
                UTF8_BOUNDARY_FILE.equals(name)) return "text/plain";
        if (HTML_FILE.equals(name)) return "text/html";
        if (INVALID_PDF.equals(name)) return "application/pdf";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        String[] columns = projection != null
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                row[index] = uri.getLastPathSegment();
            } else if (OpenableColumns.SIZE.equals(columns[index])) {
                row[index] = content(uri).length;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("测试 Provider 只允许读取");
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException failure) {
            FileNotFoundException wrapped = new FileNotFoundException("无法创建测试读取管道");
            wrapped.initCause(failure);
            throw wrapped;
        }
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                if (BLOCKING_FILE.equals(uri.getLastPathSegment())) {
                    blockingReadStarted.countDown();
                    releaseBlockingRead.await(10, TimeUnit.SECONDS);
                }
                output.write(content(uri));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 读取端提前关闭时结束夹具写入。
            }
        }, "share-fixture-writer").start();
        return pipe[0];
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_RESET_BLOCKING_READ.equals(method)) {
            resetBlockingRead();
            return new Bundle();
        }
        if (METHOD_AWAIT_BLOCKING_READ.equals(method)) {
            boolean ready = false;
            try {
                ready = blockingReadStarted.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Bundle result = new Bundle();
            result.putBoolean(RESULT_READY, ready);
            return result;
        }
        if (METHOD_RELEASE_BLOCKING_READ.equals(method)) {
            releaseBlockingRead();
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private static byte[] content(Uri uri) {
        String name = uri.getLastPathSegment();
        if (VALID_FILE.equals(name) || SECOND_FILE.equals(name) || BLOCKING_FILE.equals(name)) return CONTENT;
        if (UTF8_BOUNDARY_FILE.equals(name)) return UTF8_BOUNDARY_CONTENT;
        if (HTML_FILE.equals(name)) return HTML_CONTENT;
        if (INVALID_PDF.equals(name)) return FAKE_PDF;
        throw new IllegalArgumentException("未知测试文件");
    }

    private static byte[] utf8BoundaryContent() {
        StringBuilder content = new StringBuilder(8_240);
        for (int index = 0; index < 8_191; index++) content.append('a');
        content.append("界🙂\n中文边界内容");
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static void resetBlockingRead() {
        blockingReadStarted = new CountDownLatch(1);
        releaseBlockingRead = new CountDownLatch(1);
    }

    public static boolean awaitBlockingReadStarted() throws InterruptedException {
        return blockingReadStarted.await(10, TimeUnit.SECONDS);
    }

    public static void releaseBlockingRead() {
        releaseBlockingRead.countDown();
    }
}
