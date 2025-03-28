package com.zenith.feature.world.raycast;

import org.jspecify.annotations.Nullable;

public record BlockOrEntityRaycastResult(boolean hit, @Nullable BlockRaycastResult block, @Nullable EntityRaycastResult entity) {
    public static BlockOrEntityRaycastResult miss() {
        return new BlockOrEntityRaycastResult(false, null, null);
    }
    public static BlockOrEntityRaycastResult wrap(BlockRaycastResult blockRaycastResult) {
        return new BlockOrEntityRaycastResult(blockRaycastResult.hit(), blockRaycastResult, null);
    }
    public static BlockOrEntityRaycastResult wrap(EntityRaycastResult entityRaycastResult) {
        return new BlockOrEntityRaycastResult(entityRaycastResult.hit(), null, entityRaycastResult);
    }

    public boolean isEntity() {
        return entity() != null;
    }

    public boolean isBlock() {
        return block() != null;
    }
}
