package com.lingoplay.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingRunGateTest {
    @Test
    fun serializesOverlappingProcessingRuns() = runBlocking {
        val gate = ProcessingRunGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            gate.run {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()

        val second = async {
            gate.run {
                order += "second-start"
                order += "second-end"
            }
        }
        delay(50)
        assertEquals(listOf("first-start"), order)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second-start", "second-end"), order)
    }
}
