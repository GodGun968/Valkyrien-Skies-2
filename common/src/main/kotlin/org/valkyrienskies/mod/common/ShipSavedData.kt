package org.valkyrienskies.mod.common

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.saveddata.SavedData
import org.valkyrienskies.core.pipelines.VSPipeline
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.vsCore
import java.nio.file.Files
import java.nio.file.Paths

/**
 * This class saves/loads ship data for a world.
 *
 * This is only a temporary solution, and should be replaced eventually because it is very inefficient.
 */
class ShipSavedData : SavedData(SAVED_DATA_ID) {

    companion object {
        const val SAVED_DATA_ID = "vs_ship_data"
        private const val LEGACY_QUERYABLE_SHIP_DATA_NBT_KEY = "queryable_ship_data"
        private const val LEGACY_CHUNK_ALLOCATOR_NBT_KEY = "chunk_allocator"
        private const val PIPELINE_DATA_KEY = "pipeline_data"

        fun createEmpty(): ShipSavedData {
            return ShipSavedData()
        }
    }

    var pipeline: VSPipeline? = null

    override fun load(compoundTag: CompoundTag) {
        val pipelineAsBytes = compoundTag.getByteArray(PIPELINE_DATA_KEY)
        val queryableShipDataAsBytes = compoundTag.getByteArray(LEGACY_QUERYABLE_SHIP_DATA_NBT_KEY)
        val chunkAllocatorAsBytes = compoundTag.getByteArray(LEGACY_CHUNK_ALLOCATOR_NBT_KEY)

        pipeline = if (pipelineAsBytes.isNotEmpty()) {
            vsCore.newPipeline(pipelineAsBytes)
        } else if (queryableShipDataAsBytes.isNotEmpty() && chunkAllocatorAsBytes.isNotEmpty()) {
            Files.newOutputStream(Paths.get("./queryable_ship_data_legacy.dat")).use {
                it.write(queryableShipDataAsBytes)
            }

            Files.newOutputStream(Paths.get("./chunk_allocator_legacy.dat")).use {
                it.write(chunkAllocatorAsBytes)
            }

            vsCore.newPipelineLegacyData(queryableShipDataAsBytes, chunkAllocatorAsBytes)
        } else {
            vsCore.newPipeline()
        }
    }

    override fun save(compoundTag: CompoundTag): CompoundTag {
        val pipelineAsBytes = vsCore.serializePipeline(pipeline!!)
        compoundTag.putByteArray(PIPELINE_DATA_KEY, pipelineAsBytes)
        return compoundTag
    }

    /**
     * This is not efficient, but it will work for now.
     */
    override fun isDirty(): Boolean {
        return true
    }
}
