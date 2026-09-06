package com.cookie.identity.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FairBatchDrainerTest {
    @Test
    fun `gives every cleaner a turn before returning to a backlog`() {
        val calls = IntArray(4)
        val cleaners = calls.indices.map { index ->
            {
                calls[index] += 1
                10
            }
        }

        val totals = FairBatchDrainer(batchSize = 10, maxBatches = 4, maxRunDurationNanos = 1_000_000)
            .drain(cleaners)

        assertThat(calls.toList()).containsExactly(1, 1, 1, 1)
        assertThat(totals).containsExactly(10, 10, 10, 10)
    }

    @Test
    fun `stops polling a cleaner after its short final batch`() {
        val calls = IntArray(2)

        val totals = FairBatchDrainer(batchSize = 10, maxBatches = 20, maxRunDurationNanos = 1_000_000)
            .drain(
                listOf(
                    { if (calls[0]++ == 0) 10 else 3 },
                    { calls[1] += 1; 0 },
                ),
            )

        assertThat(calls.toList()).containsExactly(2, 1)
        assertThat(totals).containsExactly(13, 0)
    }

    @Test
    fun `honours the monotonic time budget`() {
        var tick = 0L
        var calls = 0
        val drainer = FairBatchDrainer(
            batchSize = 10,
            maxBatches = 20,
            maxRunDurationNanos = 4,
            nanoTime = { tick++ },
        )

        drainer.drain(listOf({ calls += 1; 10 }))

        assertThat(calls).isEqualTo(1)
    }
}
