package com.opentasker.ui.screens

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.sharing.ProfileShareDraft
import com.opentasker.core.sharing.ProfileShareLibrary
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import JSON opens the bundle review, not the share form, and the share check still gates it. */
class ProfileShareImportReviewTest {
    @Test
    fun aCleanBundleGoesToTheImportReviewUnchanged() {
        val share = shareReviewOf(bundleWith("log"))

        val review = share.asImportReview()

        assertSame(share.draft.bundle, review.bundle)
        assertEquals(share.plan, review.plan)
        assertTrue(review.plan.canImport)
    }

    @Test
    fun anActionTheShareCheckBlocksStillBlocksTheImport() {
        val share = shareReviewOf(bundleWith("app.kill"))
        assertTrue("the plain plan lets it through, so the share check is the only gate", share.plan.canImport)

        val review = share.asImportReview()

        assertFalse(review.plan.canImport)
        assertTrue(review.plan.warnings.any { it.startsWith("app.kill:") })
    }

    private fun bundleWith(actionType: String): OpenTaskerBundle = OpenTaskerBundleCodec.build(
        appVersion = "0.2.96",
        exportedAtEpochMs = 123L,
        profiles = emptyList(),
        tasks =listOf(Task(id = 1, name = "Imported", actions = listOf(ActionSpec(type = actionType)))),
    )

    private fun shareReviewOf(bundle: OpenTaskerBundle): ProfileShareReviewState {
        val draft = ProfileShareDraft(slug = "imported", title = "Imported", summary = "From a file.", bundle = bundle)
        return ProfileShareReviewState(
            draft = draft,
            manifest = ProfileShareLibrary.buildManifest(draft),
            plan = OpenTaskerBundleCodec.validate(bundle),
        )
    }
}
