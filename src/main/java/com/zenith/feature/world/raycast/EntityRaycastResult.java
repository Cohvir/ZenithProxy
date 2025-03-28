package com.zenith.feature.world.raycast;

import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.cache.data.entity.EntityStandard;
import com.zenith.mc.entity.EntityData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.jspecify.annotations.Nullable;

import static com.zenith.Shared.ENTITY_DATA;

public record EntityRaycastResult(boolean hit, @Nullable RayIntersection intersection, @Nullable Entity entity) {
    public static EntityRaycastResult miss() {
        return new EntityRaycastResult(false, null, null);
    }

    public @Nullable EntityType entityType() {
        if (entity instanceof EntityStandard e) {
            return e.getEntityType();
        } else if (entity instanceof EntityPlayer p) {
            return EntityType.PLAYER;
        }
        return null;
    }

    public @Nullable EntityData entityData() {
        if (entity == null) return null;
        return ENTITY_DATA.getEntityData(entity.getEntityType());
    }
}
