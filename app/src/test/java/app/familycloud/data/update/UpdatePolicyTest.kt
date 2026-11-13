package app.familycloud.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun asset(debug: Boolean) = ReleaseAsset(
        if (debug) "FamilyCloudApp-debug.apk" else "FamilyCloudApp.apk",
        "https://github.com/alexeygodov/FamilyCloud/releases/download/latest/" +
            if (debug) "FamilyCloudApp-debug.apk" else "FamilyCloudApp.apk",
        1024, "sha256:" + "a".repeat(64),
    )

    @Test fun selectsExactBuildVariant() {
        val assets = listOf(asset(true), asset(false))
        assertEquals(asset(true), UpdatePolicy.selectAsset(assets, true))
        assertEquals(asset(false), UpdatePolicy.selectAsset(assets, false))
    }

    @Test fun rejectsMissingDuplicateOversizedAndForeignAssets() {
        rejects { UpdatePolicy.selectAsset(listOf(asset(false)), true) }
        rejects { UpdatePolicy.selectAsset(listOf(asset(true), asset(true)), true) }
        rejects { UpdatePolicy.selectAsset(listOf(asset(true).copy(size = 0)), true) }
        rejects { UpdatePolicy.selectAsset(listOf(asset(true).copy(size = UpdatePolicy.MAX_APK_BYTES + 1)), true) }
        rejects { UpdatePolicy.selectAsset(listOf(asset(true).copy(url = "https://evil.example/app.apk")), true) }
        rejects { UpdatePolicy.selectAsset(listOf(asset(true).copy(digest = "sha256:bad")), true) }
    }

    @Test fun comparesSemanticVersionsFromReleaseNotes() {
        val notes = "Сборка\nversionName=1.0.2\nversionCode=12\n"
        assertEquals(AppVersion("1.0.2", 12), UpdatePolicy.versionFromNotes(notes))
        assertTrue(UpdatePolicy.isNewer("1.0.1", 11, AppVersion("1.0.2", 12)))
        assertTrue(UpdatePolicy.isNewer("1.0.1-debug", 11, AppVersion("1.10.0", 12)))
        assertFalse(UpdatePolicy.isNewer("1.0.2-debug", 12, AppVersion("1.0.2", 13)))
        assertFalse(UpdatePolicy.isNewer("1.0.2", 12, AppVersion("1.0.1", 13)))
        assertTrue(UpdatePolicy.matches("1.0.2-debug", 12, AppVersion("1.0.2", 12)))
        assertFalse(UpdatePolicy.matches("1.0.2", 11, AppVersion("1.0.2", 12)))
        rejects { UpdatePolicy.versionFromNotes("versionName=1.0\nversionCode=12") }
        rejects { UpdatePolicy.isNewer("1.0.1", 12, AppVersion("1.0.2", 12)) }
        rejects { UpdatePolicy.isNewer("1.0.1", 0, AppVersion("1.0.2", 12)) }
    }

    @Test fun requiresExactPackageAndNonemptyCurrentSigningSet() {
        UpdatePolicy.validateIdentity("app.familycloud", "app.familycloud", setOf("cert"), setOf("cert"))
        rejects { UpdatePolicy.validateIdentity("app.familycloud", "app.familycloud.debug", setOf("cert"), setOf("cert")) }
        rejects { UpdatePolicy.validateIdentity("p", "p", emptySet(), emptySet()) }
        rejects { UpdatePolicy.validateIdentity("p", "p", setOf("a"), setOf("b")) }
        rejects { UpdatePolicy.validateIdentity("p", "p", setOf("a"), setOf("a", "b")) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected UpdateFailure") } catch (_: UpdateFailure) { }
    }
}
