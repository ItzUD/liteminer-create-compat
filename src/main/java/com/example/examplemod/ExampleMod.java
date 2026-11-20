package com.example.examplemod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(ExampleMod.MODID)
public final class ExampleMod {
    public static final String MODID = "createliteminercompat";

    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        ManualApplicationCompat.register();
    }
}
