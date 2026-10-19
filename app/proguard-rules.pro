# Room, Media3, OkHttp and Coil ship their own consumer rules.
# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
