package com.example.navisense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoicePersonaTest {

    private var currentTimeMs: Long = 1000L
    private lateinit var persona: VoicePersona

    @Before
    fun setup() {
        currentTimeMs = 1000L
        persona = VoicePersona(timeProvider = { currentTimeMs })
    }

    @Test
    fun obstacleAlerts_naturalPhrasingAndFillerRotation() {
        // First alert gets "Careful, "
        val alert1 = persona.transform("Person ahead, 2 meters. Step left.")
        assertEquals("Careful, person ahead, about two meters. Step left.", alert1)

        // Second alert gets "Heads up, " (no back-to-back duplicate filler)
        val alert2 = persona.transform("Chair on your right, 1 meter")
        assertEquals("Heads up, a chair on your right, about one meter.", alert2)

        // Third alert rotates back to "Careful, "
        val alert3 = persona.transform("Person ahead, 0.5 meters. Step right.")
        assertEquals("Careful, person ahead, about half a meter. Step right.", alert3)
    }

    @Test
    fun urgentAlerts_stayShortAndHaveZeroFiller() {
        // "Person ahead, 1 meter. Stop." -> "Stop. Person ahead."
        val urgent1 = persona.transform("Person ahead, 1 meter. Stop.")
        assertEquals("Stop. Person ahead.", urgent1)
        assertFalse("Urgent alert must not contain filler", urgent1.contains("Careful") || urgent1.contains("Heads up"))

        // Large obstacle
        val urgent2 = persona.transform("Large obstacle ahead. Stop.")
        assertEquals("Stop. Large obstacle ahead.", urgent2)
        assertFalse("Urgent alert must not contain filler", urgent2.contains("Careful") || urgent2.contains("Heads up"))

        // Explicit isUrgent = true
        val urgent3 = persona.transform("Person ahead, 0.5 meters", isUrgent = true)
        assertEquals("Stop. Person ahead.", urgent3)
        assertFalse("Urgent alert must not contain filler", urgent3.contains("Careful") || urgent3.contains("Heads up"))
    }

    @Test
    fun reassurance_rateLimitedToOnePerMinute() {
        // First reassurance at t = 1000L
        currentTimeMs = 1000L
        val r1 = persona.maybeGetReassurance()
        assertEquals("You're on the right path.", r1)

        // 30 seconds later (t = 31000L) -> rate limited
        currentTimeMs = 31000L
        val r2 = persona.maybeGetReassurance()
        assertNull("Reassurance should be null when under 60 seconds", r2)

        // 59 seconds later (t = 60000L) -> still rate limited
        currentTimeMs = 60000L
        val r3 = persona.maybeGetReassurance()
        assertNull("Reassurance should be null when under 60 seconds", r3)

        // 60 seconds later (t = 61000L, 60s elapsed) -> triggers!
        currentTimeMs = 61000L
        val r4 = persona.maybeGetReassurance()
        assertEquals("You're on the right path.", r4)
    }

    @Test
    fun allClear_triggersOnlyAfterObstacleCleared() {
        // Initially with no obstacle:
        assertNull("No all-clear when no prior obstacle existed", persona.onObstacleCleared())

        // An obstacle is reported:
        persona.transform("Chair ahead, 2 meters. Step right.")

        // Obstacle clears:
        val clearMsg = persona.onObstacleCleared()
        assertEquals("All clear ahead.", clearMsg)

        // Calling again immediately:
        assertNull("All clear should not repeat once already cleared", persona.onObstacleCleared())
    }

    @Test
    fun navigationInstructions_naturalPhrasing() {
        // "In 30 meters, turn left onto Market Street" -> "In thirty meters, turn left onto Market Street."
        val nav1 = persona.transform("In 30 meters, turn left onto Market Street")
        assertEquals("In thirty meters, turn left onto Market Street.", nav1)

        // "Turn left now" -> "Turn left now."
        val nav2 = persona.transform("Turn left now")
        assertEquals("Turn left now.", nav2)

        // Destination reached -> "You have arrived."
        val nav3 = persona.transform("You have reached your destination")
        assertEquals("You have arrived.", nav3)
    }
}
