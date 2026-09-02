package com.android.internal.util;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.media.UnsupportedSchemeException;
import android.net.Uri;

import com.android.internal.util.CompatibleDatabaseHelper;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Slog;

/**
 * compatible Provider api add by xudq
 */


public class CompatibleContentProvider extends ContentProvider {
    static final String TAG = "CompatibleContentProvider";
    private CompatibleDatabaseHelper dbHelper;
    private static final String TABLE_COMPATIBLE_VALUE = "COMPATIBLE_VALUE";

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int CODE_COMPATIBLE_VALUE = 2;
    private static final int CODE_RECOVERY = 4;

    static {
        uriMatcher.addURI("com.android.compatibleprovider", TABLE_COMPATIBLE_VALUE, CODE_COMPATIBLE_VALUE);
        uriMatcher.addURI("com.android.compatibleprovider", "RECOVERY_VALUE", CODE_RECOVERY);
        uriMatcher.addURI("com.android.compatibleprovider", TABLE_COMPATIBLE_VALUE + "Item", 5);
    }

    @Override
    public boolean onCreate() {
        dbHelper = new CompatibleDatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        switch (uriMatcher.match(uri)) {
            case CODE_COMPATIBLE_VALUE: {
                cursor = db.query(TABLE_COMPATIBLE_VALUE, projection, selection, selectionArgs, null, null, sortOrder);
                cursor.setNotificationUri(getContext().getContentResolver(), uri);
                break;
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long id = 0;
        switch (uriMatcher.match(uri)) {
            case CODE_COMPATIBLE_VALUE:
                id = db.insertWithOnConflict(TABLE_COMPATIBLE_VALUE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                getContext().getContentResolver().notifyChange(uri, null);
                break;

            case CODE_RECOVERY:
                String packageName = values.get("PACKAGE_NAME").toString();
                CompatibleConfig.parseValueXML(getContext(), packageName);
                CompatibleDatabaseHelper codb = new CompatibleDatabaseHelper(getContext());
                codb.readCompatibles(packageName);
                break;

        }
        db.close();
        return ContentUris.withAppendedId(uri, id);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsDeleted = 0;
        switch (uriMatcher.match(uri)) {
            case CODE_COMPATIBLE_VALUE: {
                rowsDeleted = db.delete(TABLE_COMPATIBLE_VALUE, selection, selectionArgs);
                getContext().getContentResolver().notifyChange(uri, null);
                break;
            }
        }
        db.close();
        return rowsDeleted;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsUpdated = 0;
        switch (uriMatcher.match(uri)) {
            case CODE_COMPATIBLE_VALUE: {
                rowsUpdated = db.update(TABLE_COMPATIBLE_VALUE, values, selection, selectionArgs);
                break;
            }
        }
        db.close();
        return rowsUpdated;

    }


    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public void shutdown() {    
        dbHelper.close();    
        super.shutdown();
    }

}

