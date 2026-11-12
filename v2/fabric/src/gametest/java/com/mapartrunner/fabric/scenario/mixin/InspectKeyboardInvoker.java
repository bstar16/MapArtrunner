package com.mapartrunner.fabric.scenario.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Inert fixture exposure of the pinned 1.21.11 Keyboard callbacks; the driver owns every invocation gate. */
@Mixin(Keyboard.class)
public interface InspectKeyboardInvoker {
    @Invoker("onKey") void mapart$onKey(long window, int action, KeyInput input);
    @Invoker("onChar") void mapart$onChar(long window, CharInput input);
}
