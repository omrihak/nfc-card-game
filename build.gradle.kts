plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Version for both apps: CI passes the tag as -PtapVersion=1.2.3 -> versionName 1.2.3, versionCode 10203.
// Releases must only go up (self-update installs over the existing app).
val tapVersion = findProperty("tapVersion") as String?
extra["tapVersionName"] = tapVersion ?: "0.1.0"
extra["tapVersionCode"] = tapVersion?.let { v ->
    val (major, minor, patch) = requireNotNull(Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})""").matchEntire(v)) {
        "tapVersion must look like 1.2.3, got '$v'"
    }.destructured
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
} ?: 1
