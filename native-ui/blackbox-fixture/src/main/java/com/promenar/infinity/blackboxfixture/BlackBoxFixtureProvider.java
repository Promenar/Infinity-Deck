package com.promenar.infinity.blackboxfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 为发行黑盒测试提供两个无可分块文本的文档，以及一个触发索引失败的只读文本。 */
public final class BlackBoxFixtureProvider extends ContentProvider {
    private static final String PDF_NAME = "release-parser-canary.pdf";
    private static final String DOCX_NAME = "release-parser-canary-empty.docx";
    private static final String TEXT_CANARY_NAME = "release-index-canary.txt";
    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String TEXT_CANARY_MIME = "text/plain";

    @Override
    public boolean onCreate() {
        try {
            writeFixture(PDF_NAME, createTextPdf());
            writeFixture(DOCX_NAME, createEmptyDocx());
            writeFixture(TEXT_CANARY_NAME, createReleaseIndexTextCanary());
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建黑盒文档 fixture", failure);
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        String name = requireKnownPath(uri);
        String[] requested = projection == null
                ? new String[]{
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE,
                        MediaStore.MediaColumns.MIME_TYPE
                }
                : projection;
        MatrixCursor cursor = new MatrixCursor(requested, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        File file = fixtureFile(name);
        for (String column : requested) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(name);
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else if (MediaStore.MediaColumns.MIME_TYPE.equals(column)) {
                row.add(mimeForName(name));
            } else {
                throw new IllegalArgumentException("不支持的查询列：" + column);
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return mimeForName(requireKnownPath(uri));
    }

    private static String mimeForName(String name) {
        if (PDF_NAME.equals(name)) {
            return PDF_MIME;
        }
        if (DOCX_NAME.equals(name)) {
            return DOCX_MIME;
        }
        if (TEXT_CANARY_NAME.equals(name)) {
            return TEXT_CANARY_MIME;
        }
        throw new IllegalArgumentException("未知 fixture：" + name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("fixture 仅允许只读访问");
        }
        return ParcelFileDescriptor.open(
                fixtureFile(requireKnownPath(uri)),
                ParcelFileDescriptor.MODE_READ_ONLY
        );
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("fixture 不支持写入");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("fixture 不支持删除");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("fixture 不支持更新");
    }

    private String requireKnownPath(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1) {
            throw new IllegalArgumentException("拒绝目录穿越或未知路径");
        }
        String name = segments.get(0);
        if (!PDF_NAME.equals(name) && !DOCX_NAME.equals(name) && !TEXT_CANARY_NAME.equals(name)) {
            throw new IllegalArgumentException("未知 fixture：" + name);
        }
        return name;
    }

    private File fixtureFile(String name) {
        File cacheDir = contextOrThrow().getCacheDir();
        File file = new File(cacheDir, name);
        if (!file.isFile()) {
            throw new IllegalStateException("fixture 缺失：" + name);
        }
        return file;
    }

    private void writeFixture(String name, byte[] bytes) throws IOException {
        File cacheDir = contextOrThrow().getCacheDir();
        File temporary = new File(cacheDir, name + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        File destination = new File(cacheDir, name);
        if (!temporary.renameTo(destination)) {
            throw new IOException("无法原子发布 fixture：" + name);
        }
    }

    private android.content.Context contextOrThrow() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider Context 不可用");
        return context;
    }

    /** 单页 PDF，带一行 Helvetica 文字层：无文字层的 PDF 会被应用判为扫描件而索引失败。 */
    private static byte[] createTextPdf() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        List<Integer> offsets = new ArrayList<>();
        String content = "BT /F1 12 Tf 72 720 Td (Release parser canary text layer) Tj ET";
        writePdfObject(output, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        writePdfObject(output, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        writePdfObject(output, offsets, 3,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>");
        writePdfObject(output, offsets, 4,
                "<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream");
        writePdfObject(output, offsets, 5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        int xrefOffset = output.size();
        output.write("xref\n0 6\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) {
            output.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset)
                    .getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"
                + xrefOffset + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static void writePdfObject(
            ByteArrayOutputStream output,
            List<Integer> offsets,
            int number,
            String body
    ) throws IOException {
        offsets.add(output.size());
        output.write((number + " 0 obj\n" + body + "\nendobj\n")
                .getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] createEmptyDocx() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            addZipEntry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                            + "</Types>");
            addZipEntry(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                            + "</Relationships>");
            addZipEntry(zip, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                            + "<w:body><w:p/><w:sectPr/></w:body></w:document>");
        }
        return output.toByteArray();
    }

    private static void addZipEntry(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] createReleaseIndexTextCanary() {
        String content = String.join(
                "\n",
                "Rag index canary text.",
                "This line keeps MIME text/plain as stable ASCII.",
                "Used by minified blackbox smoke to cover indexing failure and retry behavior."
        );
        return content.getBytes(StandardCharsets.US_ASCII);
    }
}
