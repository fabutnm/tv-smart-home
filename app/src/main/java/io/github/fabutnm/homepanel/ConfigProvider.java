package io.github.fabutnm.homepanel;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets the install script write config.json (server address and token) into the app's private files:
 *
 *   content write --uri content://io.github.fabutnm.homepanel.config/config.json < /data/local/tmp/config.json
 *
 * Write only: nothing can read the token back through here. Callers need WRITE_SECURE_SETTINGS, which the adb
 * shell has and ordinary apps cannot get.
 */
public class ConfigProvider extends ContentProvider {
    static final String FILE_NAME = "config.json";

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment()) || uri.getPathSegments().size() != 1 || !mode.contains("w")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(getContext().getFilesDir(), FILE_NAME);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!FILE_NAME.equals(uri.getLastPathSegment())) return 0;
        return new File(getContext().getFilesDir(), FILE_NAME).delete() ? 1 : 0;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
