package com.lingoplay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingCheckpointSchemaTest {
    @Test
    fun legacyAndCurrentSchemasAreAcceptedButFutureSchemasFailClosed() {
        assertTrue(ProcessingCheckpointSchema.isSupported(0))
        assertTrue(ProcessingCheckpointSchema.isSupported(ProcessingCheckpointSchema.CURRENT_VERSION))
        assertFalse(ProcessingCheckpointSchema.isSupported(-1))
        assertFalse(ProcessingCheckpointSchema.isSupported(ProcessingCheckpointSchema.CURRENT_VERSION + 1))
    }
}
