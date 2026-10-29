package com.nekohome.translate.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLoadPolicyTest {

    private val models = listOf("Index-Translate-2B-Q4_K_M.gguf", "Hy-MT2-1.8B-Q4_K_M.gguf")

    @Test
    fun modelLoadedByTheMainScreenIsReused() {
        assertEquals(
            AutoLoadDecision.UseLoaded,
            AutoLoadPolicy.decide("/data/models/a.gguf", null, models[1], models),
        )
    }

    @Test
    fun aLoadInProgressIsAwaitedNotDuplicated() {
        assertEquals(AutoLoadDecision.WaitForLoading, AutoLoadPolicy.decide(null, "/data/models/a.gguf", models[0], models))
    }

    @Test
    fun coldStartLoadsTheLastUsedModel() {
        assertEquals(AutoLoadDecision.Load(models[1]), AutoLoadPolicy.decide(null, null, models[1], models))
    }

    @Test
    fun deletedLastModelWithSeveralLeftAsksTheUser() {
        assertEquals(AutoLoadDecision.NoModel, AutoLoadPolicy.decide(null, null, "gone.gguf", models))
        assertEquals(AutoLoadDecision.NoModel, AutoLoadPolicy.decide(null, null, null, models))
    }

    @Test
    fun aSingleModelOnDiskIsLoadedEvenWithoutHistory() {
        assertEquals(AutoLoadDecision.Load(models[0]), AutoLoadPolicy.decide(null, null, null, models.take(1)))
        assertEquals(AutoLoadDecision.Load(models[0]), AutoLoadPolicy.decide(null, null, "gone.gguf", models.take(1)))
    }

    @Test
    fun noModelsAtAll() {
        assertEquals(AutoLoadDecision.NoModel, AutoLoadPolicy.decide(null, null, models[0], emptyList()))
    }
}
