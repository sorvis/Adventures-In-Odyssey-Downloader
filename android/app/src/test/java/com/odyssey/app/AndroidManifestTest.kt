package com.odyssey.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pin down the AndroidManifest pieces that are easy to break and have
 * silent runtime consequences:
 *
 * - The androidx.startup InitializationProvider must explicitly remove
 *   `androidx.work.WorkManagerInitializer`. Without that, the default
 *   WorkManager init races HiltAndroidApp and wins with the no-arg
 *   WorkerFactory, so @AssistedInject workers (DailyCheckWorker etc.)
 *   silently fail to construct and "Check now" does nothing.
 *
 * If the manifest path can't be resolved (running outside the JVM script),
 * tests are skipped via assumption-style guards, not failed.
 */
class AndroidManifestTest {

    private val manifest by lazy {
        val path = System.getProperty("odyssey.manifest")
            ?: locateManifest()
            ?: error("Could not locate AndroidManifest.xml — pass -Dodyssey.manifest=<path>")
        val file = File(path)
        check(file.exists()) { "Manifest not found at $path" }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    @Test
    fun `InitializationProvider explicitly removes the default WorkManagerInitializer`() {
        val provider = manifest.getElementsByTagName("provider")
            .toElementList()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == "androidx.startup.InitializationProvider" }
        assertNotNull(
            "<provider androidx.startup.InitializationProvider> must exist (the application's manifest entry that disables WorkManager auto-init)",
            provider,
        )

        val workManagerMeta = provider!!.getElementsByTagName("meta-data")
            .toElementList()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == "androidx.work.WorkManagerInitializer" }
        assertNotNull(
            "Manifest is missing <meta-data android:name=\"androidx.work.WorkManagerInitializer\" tools:node=\"remove\"/>. " +
                    "Without it, the default WorkManagerInitializer races HiltAndroidApp and wins, " +
                    "the no-arg WorkerFactory can't construct @AssistedInject workers, and Check now does nothing.",
            workManagerMeta,
        )

        assertEquals(
            "tools:node on the WorkManagerInitializer meta-data must be \"remove\"",
            "remove",
            workManagerMeta!!.getAttributeNS(TOOLS_NS, "node"),
        )
    }

    @Test
    fun `OdysseyApp is registered as the application class`() {
        val app = manifest.getElementsByTagName("application").item(0) as Element
        // androidManifest uses a leading dot for relative class names.
        val name = app.getAttributeNS(ANDROID_NS, "name")
        assertTrue(
            "Expected android:name to point at OdysseyApp, was: $name",
            name == ".app.OdysseyApp" || name == "com.odyssey.app.OdysseyApp",
        )
    }

    private fun locateManifest(): String? {
        // Walk up from the working dir looking for android/app/src/main/AndroidManifest.xml.
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "android/app/src/main/AndroidManifest.xml")
            if (candidate.exists()) return candidate.absolutePath
            dir = dir?.parentFile
        }
        return null
    }

    private fun org.w3c.dom.NodeList.toElementList(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val TOOLS_NS = "http://schemas.android.com/tools"
    }
}
