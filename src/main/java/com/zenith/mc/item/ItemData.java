package com.zenith.mc.item;

import com.zenith.mc.RegistryData;
import org.jspecify.annotations.Nullable;

public record ItemData(
    int id,
    String name,
    int stackSize,
    @Nullable ToolTag toolTag
) implements RegistryData {
    public ItemData(int id, String name, int stackSize) {
        this(id, name, stackSize, null);
    }
}
