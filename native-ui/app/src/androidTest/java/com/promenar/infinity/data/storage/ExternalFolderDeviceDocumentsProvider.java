package com.promenar.infinity.data.storage;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 只安装在测试 APK，目录位于该 APK 的缓存区。
 * 系统会在测试 APK 自己的进程中创建本提供商，该进程不含 Kotlin 标准库，因此使用 Java。
 */
public final class ExternalFolderDeviceDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
    };
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    };

    private File root;

    @Override
    public boolean onCreate() {
        try {
            root = new File(getContext().getCacheDir(), "external-folder-device-fixture").getCanonicalFile();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建测试根目录");
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "root");
        values.put(Root.COLUMN_DOCUMENT_ID, "root");
        values.put(Root.COLUMN_TITLE, "Infinity Deck 文件夹测试");
        values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
        addRow(cursor, values);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addFile(cursor, resolve(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] children = resolve(parentDocumentId).listFiles();
        if (children == null) throw new FileNotFoundException("无法列出测试目录");
        Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) addFile(cursor, child);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        requireName(displayName);
        File file = new File(resolve(parentDocumentId), displayName);
        if (file.exists()) throw new IllegalStateException("测试文档已存在");
        boolean created;
        try {
            created = Document.MIME_TYPE_DIR.equals(mimeType) ? file.mkdir() : file.createNewFile();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        if (!created) throw new IllegalStateException("无法创建测试文档");
        return id(file);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        requireName(displayName);
        File file = resolve(documentId);
        File target = new File(file.getParentFile(), displayName);
        if (target.exists() || !file.renameTo(target)) throw new IllegalStateException("无法重命名测试文档");
        return id(target);
    }

    /** 与系统本地存储提供商一致：同名移入目标目录；产品覆盖写入经内部暂存目录换入时依赖此能力。 */
    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        File file = resolve(sourceDocumentId);
        if (!file.getParentFile().equals(resolve(sourceParentDocumentId))) throw new IllegalArgumentException("源父目录不匹配");
        File targetParent = resolve(targetParentDocumentId);
        if (!targetParent.isDirectory()) throw new IllegalArgumentException("目标不是目录");
        File target = new File(targetParent, file.getName());
        if (target.exists() || !file.renameTo(target)) throw new IllegalStateException("无法移动测试文档");
        return id(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if ("root".equals(documentId)) throw new IllegalArgumentException("不能删除测试根目录");
        if (!deleteRecursively(resolve(documentId))) throw new IllegalStateException("无法删除测试文档");
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/");
    }

    private File resolve(String documentId) throws FileNotFoundException {
        if (!"root".equals(documentId) && !documentId.startsWith("root/")) {
            throw new IllegalArgumentException("测试文档 ID 无效");
        }
        File file = "root".equals(documentId) ? root : new File(root, documentId.substring("root/".length()));
        File canonical;
        try {
            canonical = file.getCanonicalFile();
        } catch (IOException exception) {
            throw new FileNotFoundException("测试文档路径无效");
        }
        if (!canonical.toPath().startsWith(root.toPath())) throw new IllegalArgumentException("测试文档越界");
        if (!canonical.exists()) throw new FileNotFoundException("测试文档不存在");
        return canonical;
    }

    private static void requireName(String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("测试文档名称无效");
        }
    }

    private String id(File file) {
        if (file.equals(root)) return "root";
        return "root/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) return false;
            }
        }
        return file.delete();
    }

    private void addFile(MatrixCursor cursor, File file) {
        int flags = (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE)
                | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_MOVE;
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id(file));
        values.put(Document.COLUMN_DISPLAY_NAME, file.getName());
        values.put(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
        values.put(Document.COLUMN_SIZE, file.length());
        values.put(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        values.put(Document.COLUMN_FLAGS, flags);
        addRow(cursor, values);
    }

    private static void addRow(MatrixCursor cursor, Map<String, Object> values) {
        String[] columns = cursor.getColumnNames();
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) row[index] = values.get(columns[index]);
        cursor.addRow(row);
    }
}
