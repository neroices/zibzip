# Add project-specific ProGuard / R8 rules here.

-keep class dev.neroices.zibzip.domain.ArchiveEntryItem {
    <init>(java.lang.String, boolean, long, long, long, boolean);
}

-keep class dev.neroices.zibzip.domain.FileItem {
    <init>(java.lang.String, java.lang.String, boolean, long, long, boolean);
}

-keep interface dev.neroices.zibzip.data.NativeProgressListener {
    void onProgress(long, long, java.lang.String);
}

-keepclassmembers class * implements dev.neroices.zibzip.data.NativeProgressListener {
    void onProgress(long, long, java.lang.String);
}
