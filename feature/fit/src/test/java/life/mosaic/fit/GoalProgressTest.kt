package life.mosaic.fit

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalProgressTest {
    @Test
    fun remainingAndFractionAreCalculatedBelowGoal() {
        val result = calculateGoalProgress(consumed = 75.0, goal = 120.0)

        assertEquals(45.0, result.remaining, 0.001)
        assertEquals(0.625f, result.fraction, 0.001f)
    }

    @Test
    fun remainingDoesNotBecomeNegativeAboveGoal() {
        val result = calculateGoalProgress(consumed = 135.0, goal = 120.0)

        assertEquals(0.0, result.remaining, 0.001)
        assertEquals(1.0f, result.fraction, 0.001f)
    }

    @Test
    fun invalidInputsAreClampedToSafeValues() {
        val result = calculateGoalProgress(consumed = -10.0, goal = 0.0)

        assertEquals(0.0, result.consumed, 0.001)
        assertEquals(1.0, result.goal, 0.001)
        assertEquals(1.0, result.remaining, 0.001)
        assertEquals(0.0f, result.fraction, 0.001f)
    }
}
