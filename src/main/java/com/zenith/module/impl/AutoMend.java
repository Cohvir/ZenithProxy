package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.module.ClientBotTick;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.*;

public class AutoMend extends AbstractInventoryModule {

    int delay = 0;

    public AutoMend() {
        super(HandRestriction.OFF_HAND, 0, 50);
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleBotTick)
        );
    }

    private void handleBotTick(ClientBotTick event) {
        if (delay > 0) {
            delay--;
            return;
        }
        if (CACHE.getPlayerCache().getThePlayer().isAlive()) {
            if (MODULE.get(AutoTotem.class).isEnabled()
                && MODULE.get(AutoTotem.class).playerHealthBelowThreshold()) {
                delay = 50;
                return;
            }
            delay = doInventoryActions();
        }
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.autoMend.enabled;
    }

    @Override
    public boolean itemPredicate(final ItemStack itemStack) {
        var dataComponents = itemStack.getDataComponents();
        if (dataComponents == null) return false;
        var enchantmentComponents = dataComponents.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantmentComponents == null) return false;
        if (!enchantmentComponents.getEnchantments().containsKey(EnchantmentRegistry.MENDING.id())) return false;
        var damageComponent = dataComponents.get(DataComponentTypes.DAMAGE);
        if (damageComponent == null) return false;
        return damageComponent > 0;
    }
}
