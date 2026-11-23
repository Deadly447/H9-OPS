package io.github.deadly447.h9ops;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands an export to another app (Share…, e.g. to a map app's Import
 * from phone) without a storage permission: read-only, one file at a time
 * from the app's cache, and only to the app the user picked (the share
 * grants it read access to that one file).
 */
public class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.deadly447.h9ops.share";

    /** The folder for the file being shared; anything older in it is deleted. */
    static File freshDir(Context c) {
        File d = new File(c.getCacheDir(), "share");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File[] old = d.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        return d;
    }

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }

    private File file(Uri u) {
        String n = u.getLastPathSegment();
        if (n == null || n.isEmpty() || n.contains("/") || n.startsWith(".")) return null;
        File f = new File(new File(getContext().getCacheDir(), "share"), n);
        return f.isFile() ? f : null;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        return n != null && n.endsWith(".gpx") ? "application/gpx+xml" : "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        File f = file(uri);
        if (f == null) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
