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

    @Test fun comparesVersionCodeNotVersionName() {
        assertTrue(UpdatePolicy.isNewer(5, 6))
        assertFalse(UpdatePolicy.isNewer(6, 6))
        assertFalse(UpdatePolicy.isNewer(6, 5))
        assertTrue(UpdatePolicy.isNewer(6, Int.MAX_VALUE.toLong() + 1))
        rejects { UpdatePolicy.isNewer(5, 0) }
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
