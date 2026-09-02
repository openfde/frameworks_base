
package com.android.internal.util;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.database.Cursor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import android.content.ContentValues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.util.Slog;

import android.os.SystemProperties;


/**
 * compatible db api add by xudq
 */


public class CompatibleDatabaseHelper extends SQLiteOpenHelper {
    public static final String TAG = "CompatibleDatabaseHelper";

    static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "compatible.db";

    public static final String TABLE_NAME = "COMPATIBLE_VALUE";


    private static final String COMPATIBLE_VALUE_CREATE =
            "CREATE TABLE IF NOT EXISTS COMPATIBLE_VALUE ( _ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "PACKAGE_NAME TEXT , KEY_CODE TEXT  ,VALUE TEXT  , NOTES TEXT, ACTIVITY_NAME TEXT, IS_ENABLE TEXT, CREATE_DATE TEXT,EDIT_DATE TEXT,APP_NAME TEXT,FIELDS1 TEXT,FIELDS2 TEXT,IS_DEL TEXT, UNIQUE(PACKAGE_NAME,ACTIVITY_NAME, KEY_CODE))";

    private static final String COMPATIBLE_VALUE_INDEX =
            "CREATE INDEX PACKAGE_V_INDEX ON COMPATIBLE_VALUE (PACKAGE_NAME)";


    public CompatibleDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }


    @Override
    public void onCreate(SQLiteDatabase db) {
        createTableSQL(db);
    }

    private void createTableSQL(SQLiteDatabase db) {
        db.execSQL(COMPATIBLE_VALUE_CREATE);
        db.execSQL(COMPATIBLE_VALUE_INDEX);
    }

    private void dropTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE COMPATIBLE_VALUE");
    }

    @Override
     public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    public void readCompatibles() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME, null);

        if (cursor.moveToFirst()) {
            do {
                //int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
                String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
                String ACTIVITY_NAME = cursor.getString(cursor.getColumnIndex("ACTIVITY_NAME"));
                //String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
                //String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));

                String key = PACKAGE_NAME + "_" + KEY_CODE;
                if(ACTIVITY_NAME !=null && !"".equals(ACTIVITY_NAME)){
                    key = PACKAGE_NAME + "_" + KEY_CODE+ "_"+ACTIVITY_NAME;
                }
                String value = VALUE;
                if ("1".equals(IS_DEL)) {
                    value = "";
                }
                if(value !=null && value.length()> 91){
                    SystemProperties.set(key, "");
                }else{
                    SystemProperties.set(key, value);
                }
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
    }

    public void readCompatibles(String packageName) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selection = "PACKAGE_NAME = ?";
        String[] selectionArgs = {packageName};
        Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs, null, null, null);

        if (cursor.moveToFirst()) {
            do {
                //int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
                String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
                String ACTIVITY_NAME = cursor.getString(cursor.getColumnIndex("ACTIVITY_NAME"));
                //String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
                //String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));

                String key = PACKAGE_NAME + "_" + KEY_CODE;
                if(ACTIVITY_NAME !=null && !"".equals(ACTIVITY_NAME)){
                    key = PACKAGE_NAME + "_" + KEY_CODE+ "_"+ACTIVITY_NAME;
                }
                String value = VALUE;
                if ("1".equals(IS_DEL)) {
                    value = "";
                }
                if(value !=null && value.length()> 91){
                    SystemProperties.set(key, "");
                }else{
                    SystemProperties.set(key, value);
                }
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
    }


    public List<Map<String, Object>> queryCompatiblesByKeyCode(String keyCode) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selection = "KEY_CODE = ?  AND IS_DEL != 1";
        String[] selectionArgs = {keyCode};
        Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs, null, null, null);

        List<Map<String, Object>> list = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                Slog.w(TAG, " queryMapValueData _ID "+_ID );
                String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
                String ACTIVITY_NAME = cursor.getString(cursor.getColumnIndex("ACTIVITY_NAME"));
                String IS_ENABLE = cursor.getString(cursor.getColumnIndex("IS_ENABLE"));
                String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
                String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
                String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
                String FIELDS1 = cursor.getString(cursor.getColumnIndex("FIELDS1"));
                Map<String, Object> mp = new HashMap<>();
                mp.put("_ID", _ID);
                mp.put("PACKAGE_NAME", PACKAGE_NAME);
                mp.put("KEY_CODE", KEY_CODE);
                mp.put("ACTIVITY_NAME", ACTIVITY_NAME);
                mp.put("IS_ENABLE", IS_ENABLE);
                mp.put("VALUE", VALUE);
                mp.put("IS_DEL", IS_DEL);
                mp.put("FIELDS1", FIELDS1);
                mp.put("CREATE_DATE", CREATE_DATE);
                mp.put("EDIT_DATE", EDIT_DATE);
                list.add(mp);
            } while (cursor.moveToNext());
        }else{
            Slog.w(TAG, " queryMapValueData is null data! " );
        }
        cursor.close();
        db.close();
        return list;
    }


    
    public List<Map<String, Object>> queryCompatiblesByAll(String keyCode,String packageName,String activityName) {
            SQLiteDatabase db = this.getReadableDatabase();
            String selection = "KEY_CODE = ?  AND PACKAGE_NAME = ?  AND  ACTIVITY_NAME = ?  AND  IS_DEL != 1";
            String[] selectionArgs = {keyCode,packageName,activityName};
            Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs, null, null, null);
    
            List<Map<String, Object>> list = new ArrayList<>();
            if (cursor.moveToFirst()) {
                do {
                    int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                    Slog.w(TAG, " queryMapValueData _ID "+_ID );
                    String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                    String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                    String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
                    String ACTIVITY_NAME = cursor.getString(cursor.getColumnIndex("ACTIVITY_NAME"));
                    String IS_ENABLE = cursor.getString(cursor.getColumnIndex("IS_ENABLE"));
                    String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
                    String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
                    String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
                    String FIELDS1 = cursor.getString(cursor.getColumnIndex("FIELDS1"));
                    Map<String, Object> mp = new HashMap<>();
                    mp.put("_ID", _ID);
                    mp.put("PACKAGE_NAME", PACKAGE_NAME);
                    mp.put("KEY_CODE", KEY_CODE);
                    mp.put("ACTIVITY_NAME", ACTIVITY_NAME);
                    mp.put("IS_ENABLE", IS_ENABLE);
                    mp.put("VALUE", VALUE);
                    mp.put("IS_DEL", IS_DEL);
                    mp.put("FIELDS1", FIELDS1);
                    mp.put("CREATE_DATE", CREATE_DATE);
                    mp.put("EDIT_DATE", EDIT_DATE);
                    list.add(mp);
                } while (cursor.moveToNext());
            }else{
                Slog.w(TAG, " queryMapValueData is null data! " );
            }
            cursor.close();
            db.close();
            return list;
        }


    public Map<String, Object> queryMapValueData(String selection, String[] selectionArgs) {
        SQLiteDatabase db = this.getReadableDatabase();
        String queryParam = selection + " AND IS_DEL != 1";
      
        StringBuilder sb = new StringBuilder();
        for (String s : selectionArgs) {
            sb.append(s).append("###");
        }
        Slog.w(TAG, "queryMapValueData Array elements: " + sb.toString().trim());
        Cursor cursor = db.query(TABLE_NAME, null, queryParam, selectionArgs, null, null, null);
        Map<String, Object> mp = new HashMap<>();
        if (cursor != null && cursor.moveToFirst()) {
            int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
            String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
            String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
            String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
            String IS_DEL = cursor.getString(cursor.getColumnIndex("IS_DEL"));
            String CREATE_DATE = cursor.getString(cursor.getColumnIndex("CREATE_DATE"));
            String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
            String FIELDS1 = cursor.getString(cursor.getColumnIndex("FIELDS1"));
            String ACTIVITY_NAME = cursor.getString(cursor.getColumnIndex("ACTIVITY_NAME"));
            String IS_ENABLE = cursor.getString(cursor.getColumnIndex("IS_ENABLE"));
            mp.put("_ID", _ID);
            mp.put("PACKAGE_NAME", PACKAGE_NAME);
            mp.put("KEY_CODE", KEY_CODE);
            mp.put("VALUE", VALUE);
            mp.put("IS_DEL", IS_DEL);
            mp.put("CREATE_DATE", CREATE_DATE);
            mp.put("EDIT_DATE", EDIT_DATE);
            mp.put("FIELDS1", FIELDS1);
            mp.put("ACTIVITY_NAME", ACTIVITY_NAME);
            mp.put("IS_ENABLE", IS_ENABLE);
        }
        cursor.close();
        db.close();
        return mp;
    }

    public String queryStringValueData(String selection, String[] selectionArgs) {
        SQLiteDatabase db = this.getReadableDatabase();
        String queryParam = selection + " AND IS_DEL != 1";
        Slog.w(TAG, " queryMapValueData queryParam "+queryParam );
        StringBuilder sb = new StringBuilder();
        for (String s : selectionArgs) {
            sb.append(s).append("###");
        }
        Slog.w(TAG, "queryMapValueData Array elements: " + sb.toString().trim());

        String sql = "SELECT VALUE FROM "+TABLE_NAME + " WHERE "+queryParam +";";
        Cursor cursor = db.rawQuery(sql, selectionArgs);
        // Cursor cursor = db.query(TABLE_NAME, null, queryParam, selectionArgs, null, null, null);
        String VALUE  = null;
        if (cursor != null && cursor.moveToFirst()) {
            VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
            Slog.w(TAG, " queryMapValueData VALUE "+VALUE );
        }else{
            Slog.e(TAG, " queryMapValueData VALUE is null, sql:"+sql );
        }
        cursor.close();
        db.close();
        return VALUE;
    }


    public void insertCompatible(String packageName, String keyCode,String activityName, String value) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        String curTime = getCurDateTime();
        values.put("PACKAGE_NAME", packageName);
        values.put("KEY_CODE", keyCode);
        values.put("VALUE", value);
        values.put("IS_DEL", "0");
        values.put("IS_ENABLE", "1");
        values.put("APP_NAME", "");
        values.put("CREATE_DATE", curTime);
        values.put("EDIT_DATE", curTime);
        values.put("FIELDS1", getCurDate());
        values.put("FIELDS2", "");
        values.put("ACTIVITY_NAME", activityName);
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        db.close();
    }


    public int updateCompatible(String packageName, String keycode, String activityName,String value, String date) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("VALUE", value);
        values.put("IS_DEL", "0");
        values.put("FIELDS1", date);
        values.put("ACTIVITY_NAME", activityName);
        values.put("EDIT_DATE", getCurDateTime());
        int res = db.updateWithOnConflict(TABLE_NAME, values, "PACKAGE_NAME = ? AND KEY_CODE = ? ", new String[]{packageName, keycode},SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
        return res;
    }


    public int deleteCompatibleByPackageName(String packageName) {
        Slog.w(TAG, "deleteCompatibleByPackageName " + packageName);
        SQLiteDatabase db = this.getWritableDatabase();
        int res = db.delete(TABLE_NAME, "PACKAGE_NAME = ? ", new String[]{packageName});
        db.close();
        return res;
    }

    public int deleteCompatibleByKeyCode(String keyCode) {
        Slog.w(TAG, "deleteCompatibleByKeyCode " + keyCode);
        SQLiteDatabase db = this.getWritableDatabase();
        int res = db.delete(TABLE_NAME, "KEY_CODE = ? AND FIELDS1 !='' ", new String[]{keyCode});
        db.close();
        return res;
    }


    public int deleteCompatible(String packageName, String keyCode) {
        Slog.w(TAG, "deleteCompatible packageName " + packageName + ",keyCode: "+keyCode);
        SQLiteDatabase db = this.getWritableDatabase();
        int res = db.delete(TABLE_NAME, "PACKAGE_NAME = ? AND KEY_CODE = ? ", new String[]{packageName, keyCode});
        db.close();
        return res;
    }

    public void deleteAllCompatibles() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NAME, null, null);
        db.close();
    }

    public static String getCurDate() {
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String formattedTime = currentTime.format(formatter);
        return formattedTime;
    }

    public static String getCurDateTime() {
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedTime = currentTime.format(formatter);
        return formattedTime;
    }


}

