package com.zenith.cache.data.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.jspecify.annotations.NonNull;

@Data
@AllArgsConstructor
@Accessors(chain = true)
public class PotionEffect {
    @NonNull
    public final Effect effect;
    public int amplifier;
    public int duration;
    public boolean ambient;
    public boolean showParticles;
    public boolean showIcon;
    public boolean blend;
}
