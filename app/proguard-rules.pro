# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent {}

# Keep Firebase (to be added later)
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

