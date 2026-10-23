# Models are persisted explicitly; no reflective serialization is used.

# WorkManager's transitive Room 2.6 database is instantiated by reflection during
# AndroidX Startup, before Application.onCreate. Its consumer rules keep the
# database class but omit the no-arg constructor under current R8 full mode.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
