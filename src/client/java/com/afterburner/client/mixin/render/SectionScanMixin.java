package com.afterburner.client.mixin.render;

import com.afterburner.client.render.LightWaitSection;
import com.afterburner.client.render.SectionScans;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/** The game's per-frame looks through the visible sections, for rebuilds and for block entities ({@link SectionScans}). */
@Mixin(LevelRenderer.class)
public class SectionScanMixin {
	private static final String VISIBLE = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;";

	@ModifyExpressionValue(method = "compileSections", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = VISIBLE))
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> afterburner$toRebuild(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		return SectionScans.toRebuild(visible);
	}

	@WrapOperation(method = "compileSections", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;lightOnInSection(Lnet/minecraft/core/SectionPos;)Z"))
	private boolean afterburner$lightOn(LevelLightEngine engine, SectionPos pos, Operation<Boolean> original,
			@Local SectionRenderDispatcher.RenderSection section) {
		return SectionScans.lightOn((LightWaitSection) section, () -> original.call(engine, pos));
	}

	@ModifyExpressionValue(method = "renderLevel",
			slice = @Slice(from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=blockentities")),
			at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = VISIBLE, ordinal = 0))
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> afterburner$withBlockEntities(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		return SectionScans.withBlockEntities(visible);
	}
}
