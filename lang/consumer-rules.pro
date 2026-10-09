# Sudachi (JapaneseModel.kt) builds its plugins from class names in its bundled sudachi.json, by reflection.
-keep class com.worksap.nlp.sudachi.** { *; }
-keep class com.worksap.nlp.dartsclone.** { *; }
-dontwarn com.worksap.nlp.**
-dontwarn javax.json.**
-keep class org.glassfish.json.** { *; }
-dontwarn org.glassfish.json.**
