package com.android.internal.util;

// import androidx.room.ColumnInfo;
// import androidx.room.Entity;
// import androidx.room.Index;
// import androidx.room.PrimaryKey;

// @Entity(tableName = "COMPATIBLE_VALUE", indices ={@Index(name = "unique_index", value ={"PACKAGE_NAME", "ACTIVITY_NAME", "KEY_CODE"}, unique = true)})
// public class CompatibleValue {
//     @PrimaryKey(autoGenerate = true)
//     @ColumnInfo(name = "_ID")
//     private int id ;

//     private String packageName ;
//     @ColumnInfo(name = "PACKAGE_NAME")

//     private String activityName ;
//     @ColumnInfo(name = "ACTIVITY_NAME")

//     private String keyCode ;
//     @ColumnInfo(name = "KEY_CODE")

//     private String value ;
//     @ColumnInfo(name = "VALUE")

//     private String notes ;
//     @ColumnInfo(name = "NOTES")

//     private String isEnable;
//     @ColumnInfo(name = "IS_ENABLE")

//     private String appName ;
//     @ColumnInfo(name = "APP_NAME")

//     private String createDate;
//     @ColumnInfo(name = "CREATE_DATE")

//     private String editDate;
//     @ColumnInfo(name = "EDIT_DATE")

//     private String isDel;
//     @ColumnInfo(name = "IS_DEL")

//     private String fields1;
//     @ColumnInfo(name = "FIELDS1")

//     private String fields2;
//     @ColumnInfo(name = "FIELDS2")

//     public int getId() {
//         return id;
//     }

//     public void setId(int id) {
//         this.id = id;
//     }

//     public String getPackageName() {
//         return packageName;
//     }

//     public void setPackageName(String packageName) {
//         this.packageName = packageName;
//     }

//     public String getActivityName() {
//         return activityName;
//     }

//     public void setActivityName(String activityName) {
//         this.activityName = activityName;
//     }

//     public String getKeyCode() {
//         return keyCode;
//     }

//     public void setKeyCode(String keyCode) {
//         this.keyCode = keyCode;
//     }

//     public String getValue() {
//         return value;
//     }

//     public void setValue(String value) {
//         this.value = value;
//     }

//     public String getNotes() {
//         return notes;
//     }

//     public void setNotes(String notes) {
//         this.notes = notes;
//     }

//     public String getIsEnable() {
//         return isEnable;
//     }

//     public void setIsEnable(String isEnable) {
//         this.isEnable = isEnable;
//     }

//     public String getAppName() {
//         return appName;
//     }

//     public void setAppName(String appName) {
//         this.appName = appName;
//     }

//     public String getCreateDate() {
//         return createDate;
//     }

//     public void setCreateDate(String createDate) {
//         this.createDate = createDate;
//     }

//     public String getEditDate() {
//         return editDate;
//     }

//     public void setEditDate(String editDate) {
//         this.editDate = editDate;
//     }

//     public String getIsDel() {
//         return isDel;
//     }

//     public void setIsDel(String isDel) {
//         this.isDel = isDel;
//     }

//     public String getFields1() {
//         return fields1;
//     }

//     public void setFields1(String fields1) {
//         this.fields1 = fields1;
//     }

//     public String getFields2() {
//         return fields2;
//     }

//     public void setFields2(String fields2) {
//         this.fields2 = fields2;
//     }


//     @Override
//     public String toString() {
//         return "CompatibleValue{" +
//                 "id=" + id +
//                 ", packageName='" + packageName + '\'' +
//                 ", activityName='" + activityName + '\'' +
//                 ", keyCode='" + keyCode + '\'' +
//                 ", value='" + value + '\'' +
//                 ", remarks='" + remarks + '\'' +
//                 ", isEnable='" + isEnable + '\'' +
//                 ", appName='" + appName + '\'' +
//                 ", createDate='" + createDate + '\'' +
//                 ", editDate='" + editDate + '\'' +
//                 ", isDel='" + isDel + '\'' +
//                 ", fields1='" + fields1 + '\'' +
//                 ", fields2='" + fields2 + '\'' +
//                 '}';
//     }

// }
