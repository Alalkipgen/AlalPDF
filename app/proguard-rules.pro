# Android PdfRenderer is accessed through the platform API and must remain discoverable.
-keep class android.graphics.pdf.** { *; }

# Room discovers database, entity, and DAO metadata at runtime.
-keep class com.alalkipgen.alalpdf.data.** { *; }
-keep class androidx.room.** { *; }

# Compose and coroutine state may be referenced through generated/runtime metadata.
-keep class androidx.compose.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**