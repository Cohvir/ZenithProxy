package com.zenith.feature.world;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.mc.block.*;
import com.zenith.mc.dimension.DimensionData;
import com.zenith.mc.dimension.DimensionRegistry;
import com.zenith.util.math.MathHelper;
import com.zenith.util.math.MutableVec3d;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import lombok.experimental.UtilityClass;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.zenith.Shared.*;

@UtilityClass
public class World {
    @Nullable
    public ChunkSection getChunkSection(final int x, final int y, final int z) {
        try {
            return CACHE.getChunkCache().getChunkSection(x, y, z );
        } catch (final Exception e) {
            CLIENT_LOG.error("error finding chunk section for pos: {}, {}, {}", x, y, z, e);
        }
        return null;
    }

    @Nullable
    public static Chunk getChunk(final int chunkX, final int chunkZ) {
        return CACHE.getChunkCache().get(chunkX, chunkZ);
    }

    // falls back to overworld if current dimension is null
    public @NonNull DimensionData getCurrentDimension() {
        DimensionData currentDimension = CACHE.getChunkCache().getCurrentDimension();
        if (currentDimension == null) return DimensionRegistry.OVERWORLD;
        return currentDimension;
    }

    public boolean isChunkLoadedBlockPos(final int blockX, final int blockZ) {
        return CACHE.getChunkCache().isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    public boolean isChunkLoadedChunkPos(final int chunkX, final int chunkZ) {
        return CACHE.getChunkCache().isChunkLoaded(chunkX, chunkZ);
    }

    public int getBlockStateId(final BlockPos blockPos) {
        return getBlockStateId(blockPos.x(), blockPos.y(), blockPos.z());
    }

    public int getBlockStateId(final int x, final int y, final int z) {
        final ChunkSection chunk = getChunkSection(x, y, z);
        if (chunk == null) return 0;
        return chunk.getBlock(x & 15, y & 15, z & 15);
    }

    public BlockState getBlockState(final BlockPos blockPos) {
        return getBlockState(blockPos.x(), blockPos.y(), blockPos.z());
    }

    public BlockState getBlockState(final long blockPos) {
        return getBlockState(BlockPos.getX(blockPos), BlockPos.getY(blockPos), BlockPos.getZ(blockPos));
    }

    public BlockState getBlockState(final int x, final int y, final int z) {
        return new BlockState(getBlock(x, y, z), getBlockStateId(x, y, z), x, y, z);
    }

    public Block getBlock(final BlockPos blockPos) {
        return getBlock(blockPos.x(), blockPos.y(), blockPos.z());
    }

    public Block getBlock(final long blockPos) {
        return getBlock(BlockPos.getX(blockPos), BlockPos.getY(blockPos), BlockPos.getZ(blockPos));
    }

    public Block getBlock(final int x, final int y, final int z) {
        Block blockData = BLOCK_DATA.getBlockDataFromBlockStateId(getBlockStateId(x, y, z));
        if (blockData == null)
            return BlockRegistry.AIR;
        return blockData;
    }

    public List<LocalizedCollisionBox> getIntersectingCollisionBoxes(final LocalizedCollisionBox cb) {
        final List<LocalizedCollisionBox> boundingBoxList = new ArrayList<>();
        getSolidBlockCollisionBoxes(cb, boundingBoxList);
        getEntityCollisionBoxes(cb, boundingBoxList);
        return boundingBoxList;
    }

    public void getSolidBlockCollisionBoxes(final LocalizedCollisionBox cb, final List<LocalizedCollisionBox> results) {
        LongList blockPosList = getBlockPosLongListInCollisionBox(cb);
        for (int i = 0; i < blockPosList.size(); i++) {
            var blockPos = blockPosList.getLong(i);
            final BlockState blockState = getBlockState(blockPos);
            if (blockState.isSolidBlock()) {
                var collisionBoxes = blockState.getLocalizedCollisionBoxes();
                results.addAll(collisionBoxes);
            }
        }
    }

    public void getEntityCollisionBoxes(final LocalizedCollisionBox cb, final List<LocalizedCollisionBox> results) {
        for (var entity : CACHE.getEntityCache().getEntities().values()) {
            EntityType entityType = entity.getEntityType();
            if (!(entityType == EntityType.BOAT || entityType == EntityType.SHULKER))
                continue;
            if (entity.getPassengerIds().contains(CACHE.getPlayerCache().getThePlayer().getEntityId()))
                continue;
            var entityData = ENTITY_DATA.getEntityData(entityType);
            if (entityData == null) continue;
            var x = entity.getX();
            var y = entity.getY();
            var z = entity.getZ();
            double halfW = entityData.width() / 2.0;
            double minX = x - halfW;
            double minY = y;
            double minZ = z - halfW;
            double maxX = x + halfW;
            double maxY = y + entityData.height();
            double maxZ = z + halfW;
            if (cb.intersects(minX, maxX, minY, maxY, minZ, maxZ)) {
                results.add(new LocalizedCollisionBox(minX, maxX, minY, maxY, minZ, maxZ, x, y, z));
            }
        }
    }

    public boolean isWater(Block block) {
        return block == BlockRegistry.WATER
            || block == BlockRegistry.BUBBLE_COLUMN;
    }

    public boolean isFluid(Block block) {
        return isWater(block) || block == BlockRegistry.LAVA;
    }

    @Nullable
    public FluidState getFluidState(int blockStateId) {
        return BLOCK_DATA.getFluidState(blockStateId);
    }

    public LongList getBlockPosLongListInCollisionBox(final LocalizedCollisionBox cb) {
        int minX = MathHelper.floorI(cb.minX()) - 1;
        int maxX = MathHelper.ceilI(cb.maxX()) + 1;
        int minY = MathHelper.floorI(cb.minY()) - 1;
        int maxY = MathHelper.ceilI(cb.maxY()) + 1;
        int minZ = MathHelper.floorI(cb.minZ()) - 1;
        int maxZ = MathHelper.ceilI(cb.maxZ()) + 1;
        final LongArrayList blockPosList = new LongArrayList((maxX - minX) * (maxY - minY) * (maxZ - minZ));
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    blockPosList.add(BlockPos.asLong(x, y, z));
                }
            }
        }
        return blockPosList;
    }

    public LongList getBlockPosLongListInCollisionBoxInside(final LocalizedCollisionBox cb) {
        int minX = MathHelper.floorI(cb.minX());
        int maxX = MathHelper.ceilI(cb.maxX());
        int minY = MathHelper.floorI(cb.minY());
        int maxY = MathHelper.ceilI(cb.maxY());
        int minZ = MathHelper.floorI(cb.minZ());
        int maxZ = MathHelper.ceilI(cb.maxZ());
        final LongArrayList blockPosList = new LongArrayList((maxX - minX) * (maxY - minY) * (maxZ - minZ));
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    blockPosList.add(BlockPos.asLong(x, y, z));
                }
            }
        }
        return blockPosList;
    }

    public List<BlockState> getCollidingBlockStates(final LocalizedCollisionBox cb) {
        final List<BlockState> blockStates = new ArrayList<>(4);
        LongList blockPosList = getBlockPosLongListInCollisionBox(cb);
        for (int i = 0; i < blockPosList.size(); i++) {
            var blockPos = blockPosList.getLong(i);
            var blockState = getBlockState(blockPos);
            if (BLOCK_DATA.isAir(blockState.block())) continue; // air
            List<LocalizedCollisionBox> blockStateCBs = blockState.getLocalizedCollisionBoxes();
            for (int j = 0; j < blockStateCBs.size(); j++) {
                if (blockStateCBs.get(j).intersects(cb)) {
                    blockStates.add(blockState);
                    break;
                }
            }
        }
        return blockStates;
    }

    public List<BlockState> getCollidingBlockStatesInside(final LocalizedCollisionBox cb) {
        final List<BlockState> blockStates = new ArrayList<>(4);
        LongList blockPosList = getBlockPosLongListInCollisionBoxInside(cb);
        for (int i = 0; i < blockPosList.size(); i++) {
            var blockPos = blockPosList.getLong(i);
            var blockState = getBlockState(blockPos);
            if (BLOCK_DATA.isAir(blockState.block())) continue; // air
            blockStates.add(blockState);
        }
        return blockStates;
    }

    public static boolean isSpaceEmpty(final LocalizedCollisionBox cb) {
        LongList blockPosList = getBlockPosLongListInCollisionBox(cb);
        for (int i = 0; i < blockPosList.size(); i++) {
            var blockPos = blockPosList.getLong(i);
            var blockStateCBs = getBlockState(blockPos).getLocalizedCollisionBoxes();
            for (int j = 0; j < blockStateCBs.size(); j++) {
                if (blockStateCBs.get(j).intersects(cb)) return false;
            }
        }
        return true;
    }

    public static Optional<BlockPos> findSupportingBlockPos(final LocalizedCollisionBox cb) {
        BlockPos supportingBlock = null;
        double dist = Double.MAX_VALUE;
        LongList blockPosList = getBlockPosLongListInCollisionBox(cb);
        for (int i = 0; i < blockPosList.size(); i++) {
            var blockPos2 = blockPosList.getLong(i);
            var blockState = getBlockState(blockPos2);
            var x = blockState.x();
            var y = blockState.y();
            var z = blockState.z();
            var blockStateCBs = getBlockState(blockPos2).getLocalizedCollisionBoxes();
            for (int j = 0; j < blockStateCBs.size(); j++) {
                if (blockStateCBs.get(j).intersects(cb)) {
                    final double curDist = MathHelper.distanceSq3d(x + 0.5, y + 0.5, z + 0.5, cb.x(), cb.y(), cb.z());
                    if (curDist < dist || curDist == dist && (supportingBlock == null || BlockPos.compare(supportingBlock.x(), supportingBlock.y(), supportingBlock.z(), x, y, z) < 0)) {
                        supportingBlock = new BlockPos(x, y, z);
                        dist = curDist;
                    }
                    break;
                }
            }
        }
        return Optional.ofNullable(supportingBlock);
    }

    public static MutableVec3d getFluidFlow(int x, int y, int z) {
        return getFluidFlow(getBlockState(x, y, z));
    }

    public static MutableVec3d getFluidFlow(BlockState localBlockState) {
        FluidState fluidState = getFluidState(localBlockState.id());
        if (fluidState == null) return new MutableVec3d(0, 0, 0);
        float fluidHeight = getFluidHeight(fluidState);
        if (fluidHeight == 0) return new MutableVec3d(0, 0, 0);
        double flowX = 0;
        double flowZ = 0;
        for (var dir : Direction.HORIZONTALS) {
            int x = localBlockState.x() + dir.x();
            int y = localBlockState.y();
            int z = localBlockState.z() + dir.z();
            var adjacentBlockstateId = getBlockStateId(x, y, z);
            FluidState adjacentFluidState = getFluidState(adjacentBlockstateId);
            if (affectsFlow(fluidState, adjacentFluidState)) {
                float fluidHDiffMult = 0.0F;
                float offsetFluidHeight = getFluidHeight(adjacentFluidState);
                if (offsetFluidHeight == 0) {
                    FluidState adjacentBelowFluidState = getFluidState(getBlockStateId(x, y - 1, z));
                    if (affectsFlow(fluidState, adjacentBelowFluidState)) {
                        offsetFluidHeight = getFluidHeight(adjacentBelowFluidState);
                        if (offsetFluidHeight > 0) {
                            fluidHDiffMult = fluidHeight - (offsetFluidHeight - 0.8888889F);
                        }
                    }
                } else if (offsetFluidHeight > 0) {
                    fluidHDiffMult = fluidHeight - offsetFluidHeight;
                }

                if (fluidHDiffMult != 0) {
                    flowX += (float) dir.x() * fluidHDiffMult;
                    flowZ += (float) dir.z() * fluidHDiffMult;
                }
            }
        }
        var flowVec = new MutableVec3d(flowX, 0, flowZ);

        if (fluidState.falling()) {
            for (var dir : Direction.HORIZONTALS) {
                var blockState = getBlockState(localBlockState.x() + dir.x(), localBlockState.y(), localBlockState.z() + dir.z());
                var blockStateAbove = getBlockState(localBlockState.x() + dir.x(), localBlockState.y() + 1, localBlockState.z() + dir.z());
                if (blockState.isSolidBlock() || blockStateAbove.isSolidBlock()) {
                    flowVec.normalize();
                    flowVec.add(0, -6, 0);
                    break;
                }
            }
        }
        flowVec.normalize();
        return flowVec;
    }

    public static float getFluidHeight(final FluidState fluidState) {
        if (fluidState == null) return 0;
        return fluidState.amount() / 9.0f;
    }

    public static boolean affectsFlow(FluidState inType, FluidState fluidState) {
        if (fluidState == null) return true;
        if (inType.water() && fluidState.water()) return true;
        if (inType.lava() && fluidState.lava()) return true;
        return false;
    }

    public static FluidState getFluidState(final int x, final int y, final int z) {
        return getFluidState(getBlockState(x, y, z).id());
    }

    public static boolean onClimbable(EntityLiving entity) {
        Block inBlock = getBlock(MathHelper.floorI(entity.getX()), MathHelper.floorI(entity.getY()), MathHelper.floorI(entity.getZ()));
        if (inBlock.blockTags().contains(BlockTags.CLIMBABLE)) {
            return true;
        } else if (inBlock.name().endsWith("_trapdoor")) {
            Block belowBlock = getBlock(MathHelper.floorI(entity.getX()), MathHelper.floorI(entity.getY()) - 1, MathHelper.floorI(entity.getZ()));
            if (belowBlock.blockTags().contains(BlockTags.CLIMBABLE)) {
                return true;
            }
        }
        return false;
    }

    public static double getCurrentPlayerX() {
        return MathHelper.round(CACHE.getPlayerCache().getX(), 5);
    }

    public static double getCurrentPlayerY() {
        return MathHelper.round(CACHE.getPlayerCache().getY(), 5);
    }

    public static double getCurrentPlayerZ() {
        return MathHelper.round(CACHE.getPlayerCache().getZ(), 5);
    }
}
