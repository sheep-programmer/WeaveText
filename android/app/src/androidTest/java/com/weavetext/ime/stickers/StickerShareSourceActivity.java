package com.weavetext.ime.stickers;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;

/** Executes as the source package's UID; instrumentation itself runs as the receiver UID. */
public final class StickerShareSourceActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri=Uri.parse("content://com.weavetext.ime.test.stickers/animated.gif");
        File file=new File(getCacheDir(),"sticker-tests/animated.gif");
        if (getIntent().getBooleanExtra("revoke",false)) {
            revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);file.delete();finish();return;
        }
        try {
            file.getParentFile().mkdirs();
            try(InputStream input=getAssets().open("stickers/animated.gif");FileOutputStream output=new FileOutputStream(file)) {
                byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))>=0) output.write(buffer,0,n);
            }
            grantUriPermission("com.weavetext.ime",uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent share=new Intent(Intent.ACTION_SEND).setClassName("com.weavetext.ime","com.weavetext.ime.stickers.StickerActivity");
            share.setType("image/gif");share.putExtra(Intent.EXTRA_STREAM,uri);share.setClipData(ClipData.newUri(getContentResolver(),"动图",uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(share);
        } catch(Exception e) {throw new IllegalStateException("Test sender failed",e);}
        finish();
    }
}
