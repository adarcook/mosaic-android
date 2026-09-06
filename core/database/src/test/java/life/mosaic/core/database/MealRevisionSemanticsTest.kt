package life.mosaic.core.database

import life.mosaic.core.model.LocalMealRecordIdentity
import life.mosaic.core.model.MealItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MealRevisionSemanticsTest {
    @Test
    fun `new meal starts at revision one with stable uuid identity`() {
        val identity = nextRecordIdentity(
            current = null,
            newMealId = { "11111111-1111-1111-1111-111111111111" }
        )

        assertEquals("11111111-1111-1111-1111-111111111111", identity.mealId)
        assertEquals(1, identity.revision)
        assertEquals(null, identity.supersedesRevision)
    }

    @Test
    fun `correction keeps meal id and increments revision`() {
        val current = LocalMealRecordIdentity(
            mealId = "11111111-1111-1111-1111-111111111111",
            revision = 3,
            supersedesRevision = 2
        )

        val next = nextRecordIdentity(current)

        assertEquals(current.mealId, next.mealId)
        assertEquals(4, next.revision)
        assertEquals(3, next.supersedesRevision)
    }

    @Test
    fun `component ids survive edits while new components receive ids`() {
        val existingId = "22222222-2222-2222-2222-222222222222"
        val newId = "33333333-3333-3333-3333-333333333333"
        val normalized = assignStableComponentIds(
            items = listOf(
                MealItem("Chicken", "200 g", 1.0, existingId),
                MealItem("Salad", "", 1.0)
            ),
            newComponentId = { newId }
        )

        assertEquals(existingId, normalized[0].componentId)
        assertEquals(newId, normalized[1].componentId)
    }

    @Test
    fun `latest deleted revision hides meal without destroying history`() {
        val mealARevision1 = entity(
            mealId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            revision = 1,
            status = "confirmed",
            createdAt = 100L
        )
        val mealARevision2 = entity(
            mealId = mealARevision1.mealId,
            revision = 2,
            status = "deleted",
            createdAt = 100L
        )
        val mealB = entity(
            mealId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            revision = 1,
            status = "confirmed",
            createdAt = 200L
        )

        val visible = latestVisibleRevisions(
            listOf(mealARevision1, mealARevision2, mealB)
        )

        assertEquals(listOf(mealB), visible)
        assertFalse(visible.any { it.mealId == mealARevision1.mealId })
    }

    @Test
    fun `legacy identifiers migrate deterministically to valid uuids`() {
        val first = deterministicLegacyUuid("meal:manual-old-id")
        val second = deterministicLegacyUuid("meal:manual-old-id")
        val other = deterministicLegacyUuid("meal:another-id")

        assertEquals(first, second)
        assertNotEquals(first, other)
        assertTrue(runCatching { UUID.fromString(first) }.isSuccess)
    }

    private fun entity(
        mealId: String,
        revision: Int,
        status: String,
        createdAt: Long
    ) = MealEntity(
        mealId = mealId,
        revision = revision,
        supersedesRevision = if (revision > 1) revision - 1 else null,
        analysisId = "analysis-$mealId",
        createdAtEpochMillis = createdAt,
        status = status,
        caloriesKcal = 100,
        proteinG = 10.0,
        carbohydratesG = 5.0,
        fatG = 2.0,
        itemsJson = "[]",
        assumptionsJson = "[]",
        questionsJson = "[]",
        answersJson = "{}",
        originalItemsJson = "[]",
        originalNutritionJson = "{}",
        userEdited = false
    )
}
