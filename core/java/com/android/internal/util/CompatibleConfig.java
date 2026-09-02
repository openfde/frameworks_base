package com.android.internal.util;

import android.content.ContentResolver;
import android.net.Uri;
import android.database.Cursor;
import android.content.ContentValues;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;

import java.util.concurrent.CountDownLatch;

import android.os.Binder;
import android.os.Process;

import android.util.Slog;
import android.os.SystemProperties;
import android.os.UserManager;
import android.app.ActivityManager;

import java.io.File;
import java.nio.file.Files;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Scanner;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import android.content.Context;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import com.android.internal.util.CompatibleDatabaseHelper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.DisplayMetrics;
import android.os.SystemProperties;
/**
 * compatible tool api add by xudq
 */


public class CompatibleConfig {
    public static final String COMPATIBLE_STR = "com.android.compatibleprovider";
    public static final String COMPATIBLE_URI = "content://" + COMPATIBLE_STR;
    public static final String TAG = "CompatibleConfig";
    private CompatibleDatabaseHelper dbHelper;

    private static CompatibleConfig instance;
    private static final int DESIGN_WIDTH = 1920;  // Design reference width (landscape)
    private static final int DESIGN_HEIGHT = 1080; // Design reference height (landscape)
    private CompatibleConfig() {
    }

    private CompatibleConfig(Context context) {
        dbHelper = new CompatibleDatabaseHelper(context);
    }

    public static synchronized CompatibleConfig getInstance(Context context) {
        if (instance == null) {
            instance = new CompatibleConfig(context);
        }
        return instance;
    }


    /**
     * Scales a given dimension (based on a 1920x1080 design) to match the current device's screen size.
     * Width is scaled relative to 1920, and height is scaled relative to 1080 independently.
     *
     * @param context    The application context (used to retrieve display metrics)
     * @param srcWidth   Original width in pixels (based on 1920px design width)
     * @param srcHeight  Original height in pixels (based on 1080px design height)
     * @return           An array containing {scaledWidth, scaledHeight} as integers
     */
    public static int[] scaleFrom1920x1080(Context context, int srcWidth, int srcHeight) {
        DisplayMetrics dm = new DisplayMetrics();
        context.getDisplay().getRealMetrics(dm);
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
//        int screenWidth = SystemProperties.getInt("openfde.display_width", DESIGN_WIDTH);
//        int screenHeight = SystemProperties.getInt("openfde.display_height", DESIGN_HEIGHT);

        // Calculate independent scaling ratios for width and height
        float widthRatio = (float) screenWidth / DESIGN_WIDTH;
        float heightRatio = (float) screenHeight / DESIGN_HEIGHT;

        // Apply scaling ratios separately and round to nearest integer
        int targetWidth = (int) (srcWidth * widthRatio + 0.5f);
        int targetHeight = (int) (srcHeight * heightRatio + 0.5f);

        return new int[]{targetWidth, targetHeight};
    }

    /**
     * query from db 
     * @param context
     * @param selection
     * @param selectionArgs
     * @return
     */
    public static Map<String, Object> queryMapValueData(Context context, String selection, String[] selectionArgs) {
        Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
        Cursor cursor = null;
        Map<String, Object> result = null;
        String queryParam = selection + " AND IS_DEL != 1";
        try {
            ContentResolver contentResolver = context.getContentResolver();
            cursor = contentResolver.query(uri, null, queryParam, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
                String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
                String ACTIVITY_NAME = cursor.getString(cursor.getColumnIndex("ACTIVITY_NAME"));
                String IS_ENABLE = cursor.getString(cursor.getColumnIndex("IS_ENABLE"));
                String NOTES = cursor.getString(cursor.getColumnIndex("NOTES"));
                
                result = new HashMap<>();
                result.put("_ID", _ID);
                result.put("PACKAGE_NAME", PACKAGE_NAME);
                result.put("KEY_CODE", KEY_CODE);
                result.put("VALUE", VALUE);
                result.put("EDIT_DATE", EDIT_DATE);
                result.put("ACTIVITY_NAME", ACTIVITY_NAME);
                result.put("IS_ENABLE", IS_ENABLE);
                result.put("NOTES", NOTES);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public  List<Map<String,Object>> queryAllValueDataList(String packageName){
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String selection = "PACKAGE_NAME = ?  AND IS_DEL != 1";
        String[] selectionArgs = {packageName};
        Cursor cursor = db.query(CompatibleDatabaseHelper.TABLE_NAME, null, null, null, null, null, null);
        Slog.w(TAG, " queryMapValueData cursor "+cursor.getCount() );
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
                Map<String, Object> mp = new HashMap<>();
                mp.put("_ID", _ID);
                mp.put("PACKAGE_NAME", PACKAGE_NAME);
                mp.put("KEY_CODE", KEY_CODE);
                mp.put("ACTIVITY_NAME", ACTIVITY_NAME);
                mp.put("IS_ENABLE", IS_ENABLE);
                mp.put("VALUE", VALUE);
                mp.put("IS_DEL", IS_DEL);
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

    /**
     * 
     * @param context
     * @param selection
     * @param selectionArgs
     * @return
     */
    public static String queryStringValueDataByDb(Context context, String selection, String[] selectionArgs) {
        Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
        Cursor cursor = null;
        String result = null;
        String queryParam = selection + " AND IS_DEL != 1";
        try {
            ContentResolver contentResolver = context.getContentResolver();
            cursor = contentResolver.query(uri, null, queryParam, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                result = cursor.getString(cursor.getColumnIndex("VALUE"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }


    public static String queryStringValueData(Context context, String keyCode , String packageName) {
        String result = null;
        String fdebootCompleted = SystemProperties.get("fde.boot_completed", "0");
        Slog.d(TAG,"queryValueDataBySharedMemory fdebootCompleted: " + fdebootCompleted + ",keyCode: " + keyCode + ",packageName: " + packageName);

        if (fdebootCompleted.equals("1")) {
            return SystemProperties.get(packageName + "_" + keyCode, "");
        } else {
            return null;
        }
    }


    public static String queryStringValueData(Context context, String keyCode ,String packageName,  String activityName) {
        String result = null;
        String fdebootCompleted = SystemProperties.get("fde.boot_completed", "0");
        Slog.d(TAG,"queryValueDataBySharedMemory fdebootCompleted: " + fdebootCompleted + ",keyCode: " + keyCode + ",packageName: " + packageName+ ",activityName: " + activityName);

        if (fdebootCompleted.equals("1")) {
            if (activityName == null || "".equals(activityName)) {
                return SystemProperties.get(packageName + "_" + keyCode, "");
            }else{
                return SystemProperties.get(packageName + "_" + keyCode+ "_" + activityName, "");
            }
        } else {
            return null;
        }
    }

    public static void insertUpdateValueData(Context context,  String packageName,String activityName, String keycode,
                String value) {
            String appName = packageName;     
            ExecutorService executorService = Executors.newFixedThreadPool(1);
            CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            Map<String, Object> result = queryMapValueDataHasDel(context, packageName,activityName, keycode);
            if (result == null) {
                insertValueData(context, appName, packageName,activityName, keycode, value);
            } else {
                updateValueData(context, appName, packageName,activityName, keycode, value);
            }
            return 1;
            }, executorService);
            executorService.shutdown();
     }

    
    public static void insertValueData(Context context, String appName, String packageName,String activityName, String keycode,
                     String value) {
         try { 
            if (activityName == null || "".equals(activityName)) {
                SystemProperties.set(packageName+"_"+keycode, value);
            } else {
                SystemProperties.set(packageName+"_"+keycode+ "_" + activityName, value);
            }
             Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
             ContentValues values = new ContentValues();
             String curTime = getCurDateTime();
             values.put("PACKAGE_NAME", packageName);
             values.put("KEY_CODE", keycode);
             values.put("VALUE", value);
             values.put("IS_DEL", "0");
             values.put("CREATE_DATE",curTime);
             values.put("EDIT_DATE", curTime);
             values.put("FIELDS1", curTime);
             values.put("APP_NAME", appName);
             values.put("ACTIVITY_NAME", activityName);
             Uri resUri = context.getContentResolver()
                     .insert(uri, values);
         } catch (Exception e) {
             e.printStackTrace();
         }
     }
    
        
     public static int updateValueData(Context context, String appName, String packageName,String activityName, String keycode,
                    String newValue) {
            try {
                SystemProperties.set(packageName+"_"+keycode, newValue);
                Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
                ContentValues values = new ContentValues();
                values.put("VALUE", newValue);
                values.put("APP_NAME", appName);
                values.put("IS_DEL", "0");
                values.put("EDIT_DATE", getCurDateTime());
                String selection;
                String[] selectionArgs;
                if (activityName == null || "".equals(activityName)) {
                    selection = "PACKAGE_NAME = ? AND KEY_CODE = ?";
                    selectionArgs = new String[]{packageName, keycode};
                } else {
                    selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND ACTIVITY_NAME = ?";
                    selectionArgs = new String[]{packageName, keycode, activityName};
                }
                int res = context.getContentResolver()
                        .update(uri, values, selection,
                                selectionArgs);
                return res;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return -1;
     }

     
     public static Map<String, Object> queryMapValueDataHasDel(Context context, String packageName,String activityName, String keycode) {
            Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
            Cursor cursor = null;
            Map<String, Object> result = null;
            String selection;
            String[] selectionArgs;
            if (activityName == null || "".equals(activityName)) {
                selection = "PACKAGE_NAME = ? AND KEY_CODE = ?";
                selectionArgs = new String[]{packageName, keycode};
            } else {
                selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND ACTIVITY_NAME = ?";
                selectionArgs = new String[]{packageName, keycode, activityName};
            }
            try {
              ContentResolver contentResolver = context.getContentResolver();
              cursor = contentResolver.query(uri, null, selection, selectionArgs, null);
              if (cursor != null && cursor.moveToFirst()) {
                  int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                  String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                  String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                  String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
                  String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
                  result = new HashMap<>();
                  result.put("_ID", _ID);
                  result.put("PACKAGE_NAME", PACKAGE_NAME);
                  result.put("KEY_CODE", KEY_CODE);
                  result.put("VALUE", VALUE);
                  result.put("EDIT_DATE", EDIT_DATE);
              }

            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              if (cursor != null) {
                  cursor.close();
              }
            }
                  return result;
    }

    public static int parseValueXML(Context context, String packageName) {
        try {
            InputStream inputStream = context.getResources().openRawResource(com.android.internal.R.raw.comp_config_value);
            int res = -1;
            if ("".equals(packageName)) {
                res = parseValue(context, inputStream);
            } else {
                res = parseValue(context, inputStream, packageName);
            }
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    public static int parseValue(Context context, InputStream inputStream) {
        try {
            Slog.d(TAG,"parseValue start..........");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            Element rootElement = document.getDocumentElement();
            NodeList itemList = document.getElementsByTagName("item");
            CompatibleDatabaseHelper db = new CompatibleDatabaseHelper(context);
            for (int i = 0; i < itemList.getLength(); i++) {
                Element keycodeElement = (Element) itemList.item(i);
                String updateDate = keycodeElement.getAttribute("update_date");
                String isdel = keycodeElement.getAttribute("isdel");
                String keyCode = keycodeElement.getAttribute("key_code");
                NodeList packageList = keycodeElement.getElementsByTagName("package");
                if ("true".equals(isdel)) {
                    Slog.w(TAG,"deleteCompatibleByKeyCode "+keyCode);
                    db.deleteCompatibleByKeyCode( keyCode);
                }else{
                    /*List<Map<String,Object>> list = db.queryCompatiblesByKeyCode(keyCode);
                    if(list != null && list.size() > 0 ){
                        String queryDate = list.get(0).get("FIELDS1").toString();
                        String createDate = list.get(0).get("CREATE_DATE").toString();
                        String editDate = list.get(0).get("EDIT_DATE").toString();
                        if (!"".equals(queryDate) && !updateDate.equals(queryDate)) {
                            Slog.d(TAG,"deleteCompatibleByKeyCode "+keyCode + ",queryDate: "+queryDate +",updateDate: "+updateDate);
                            db.deleteCompatibleByKeyCode( keyCode);
                        }
                    }*/
                    for (int j = 0; j < packageList.getLength(); j++) {
                        Element packageElement = (Element) packageList.item(j);
                        String packageName = packageElement.getAttribute("name");
                        NodeList activityList = packageElement.getElementsByTagName("activity");
                        for (int k = 0; k < activityList.getLength(); k++) {
                            Element activityElement = (Element) activityList.item(k);
                            String activityName = activityElement.getAttribute("name");
                            String defaultValue = activityElement.getTextContent().replaceAll("\\s", "");
                            Slog.d(TAG,"keyCode: " + keyCode + " ,packageName: " + packageName + " ,activityName: " + activityName + " ,defaultValue: " + defaultValue);
                            String selection = null;
                            String[] selectionArgs = null; 
                            if(activityName == null || "".equals(activityName)){
                                selection = "PACKAGE_NAME = ? AND KEY_CODE = ?";
                                selectionArgs = new String[] {packageName,keyCode}; 
                            }else{
                                selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND ACTIVITY_NAME = ?";
                                selectionArgs = new String[] {packageName,keyCode, activityName}; 
                            }
                            db.insertCompatible(packageName, keyCode,activityName, defaultValue);
                        }
                    }
                }
                
            }
           db.readCompatibles();
           Slog.d(TAG,"parseValue end..........");
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG,"parseValue end.....err "+e.toString());
            return -1;
        }
        return 0;
    }

    public static int parseValue(Context context, InputStream inputStream, String recoPackageName) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            Element rootElement = document.getDocumentElement();
            NodeList itemList = document.getElementsByTagName("item");
            CompatibleDatabaseHelper db = new CompatibleDatabaseHelper(context);
            for (int i = 0; i < itemList.getLength(); i++) {
                Element keycodeElement = (Element) itemList.item(i);
                String updateDate = keycodeElement.getAttribute("update_date");
                String isdel = keycodeElement.getAttribute("isdel");
                String keyCode = keycodeElement.getAttribute("key_code");
                NodeList packageList = keycodeElement.getElementsByTagName("package");
                for (int j = 0; j < packageList.getLength(); j++) {
                    Element packageElement = (Element) packageList.item(j);
                    String packageName = packageElement.getAttribute("name");
                    if (packageName.equals(recoPackageName)) {
                        NodeList activityList = packageElement.getElementsByTagName("activity");
                        for (int k = 0; k < activityList.getLength(); k++) {
                            Element activityElement = (Element) activityList.item(k);
                            String activityName = activityElement.getAttribute("name");
                            String defaultValue = activityElement.getTextContent().replaceAll("\\s", "");
                            Slog.w(TAG,"keyCode: " + keyCode + " ,packageName: " + packageName + " ,activityName: " + activityName + " ,defaultValue: " + defaultValue);
                            String selection = null;
                            String[] selectionArgs = null; 
                            if(activityName == null || "".equals(activityName)){
                                selection = "PACKAGE_NAME = ? AND KEY_CODE = ?";
                                selectionArgs = new String[] {packageName,keyCode}; 
                            }else{
                                selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND ACTIVITY_NAME = ?";
                                selectionArgs = new String[] {packageName,keyCode, activityName}; 
                            }
                            Map<String, Object> resMap = db.queryMapValueData(selection, selectionArgs);
                            if ("true".equals(isdel)) {
                                db.deleteCompatible(packageName, keyCode);
                            } else if (resMap == null || resMap.get("PACKAGE_NAME") == null) {
                                db.insertCompatible(packageName, keyCode,activityName, defaultValue);
                            } else {
                                String queryDate = resMap.get("FIELDS1").toString();
                                if (!"".equals(queryDate) && !updateDate.equals(queryDate)) {
                                    db.updateCompatible(packageName, keyCode, activityName,defaultValue, updateDate);
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            Slog.e("parseValue", "" + e.toString());
            return -1;
        }
        return 0;
    }

    public static String getCurDateTime() {
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedTime = currentTime.format(formatter);
        return formattedTime;
    }

}
