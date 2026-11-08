package com.promenar.infinity.blackboxfixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** 以 fixture 自身身份向目标应用授予 URI 只读权限，避免 shell 冒充内容所有者。 */
public final class BlackBoxFixtureShareActivity extends Activity {
    static final String EXTRA_DOCUMENT_NAME = "document_name";
    static final String EXTRA_MIME_TYPE = "mime_type";
    static final String EXTRA_TARGET_COMPONENT = "target_component";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String name = requireExtra(EXTRA_DOCUMENT_NAME);
        String mime = requireExtra(EXTRA_MIME_TYPE);
        ComponentName target = ComponentName.unflattenFromString(requireExtra(EXTRA_TARGET_COMPONENT));
        if (target == null) {
            throw new IllegalArgumentException("目标组件格式非法");
        }
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority("com.promenar.infinity.blackboxfixture.documents")
                .appendPath(name)
                .build();
        Intent share = new Intent(Intent.ACTION_SEND)
                .setComponent(target)
                .setDataAndType(uri, mime)
                .putExtra(Intent.EXTRA_STREAM, uri);
        share.setClipData(ClipData.newRawUri("Infinity Deck black-box fixture", uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(share);
        finish();
    }

    private String requireExtra(String key) {
        String value = getIntent().getStringExtra(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 fixture 参数：" + key);
        }
        return value;
    }
}
