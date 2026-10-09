package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BuiltSection;
import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.client.render.RegionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A section that gets a new mesh, or none, is swapped into the chunk batcher's next frame (or that frame is built anew),
 * and the section's own draws there are looked up again ({@link BuiltSection}).
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class SectionChangeMixin implements BuiltSection {
	@Unique
	private volatile int afterburner$meshVersion;
	@Unique
	private int afterburner$builtAt, afterburner$builtIndex, afterburner$builtVersion;
	@Unique
	private int afterburner$frameAt, afterburner$frameIndex, afterburner$listedAt;
	/** The mesh version plus one that {@link #afterburner$hasEntities} was found at (0: not yet). */
	@Unique
	private int afterburner$entitiesSeen;
	@Unique
	private boolean afterburner$hasEntities;

	@Inject(method = "setSectionMesh", at = @At("HEAD"))
	private void afterburner$newMesh(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		if (mesh instanceof RegionMesh regional) regional.afterburner$setOwner(this);
		afterburner$meshChanged();
		ChunkBatcher.changed((SectionRenderDispatcher.RenderSection) (Object) this);
	}

	/** Again once it's set: from a worker thread, a frame built between the two could still have seen the old mesh. */
	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void afterburner$meshSet(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		afterburner$meshChanged();
		ChunkBatcher.changed((SectionRenderDispatcher.RenderSection) (Object) this);
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void afterburner$reset(CallbackInfo ci) {
		afterburner$meshChanged();
		ChunkBatcher.changed((SectionRenderDispatcher.RenderSection) (Object) this);
	}

	@Override
	public int afterburner$meshVersion() {
		return afterburner$meshVersion;
	}

	@Override
	@SuppressWarnings("NonAtomicOperationOnVolatileField")
	public void afterburner$meshChanged() {
		// Two threads at once may count one change between them, which still makes it differ.
		afterburner$meshVersion++;
	}

	@Override
	public int afterburner$builtAt() {
		return afterburner$builtAt;
	}

	@Override
	public int afterburner$builtIndex() {
		return afterburner$builtIndex;
	}

	@Override
	public int afterburner$builtVersion() {
		return afterburner$builtVersion;
	}

	@Override
	public void afterburner$built(int build, int index, int version) {
		afterburner$builtAt = build;
		afterburner$builtIndex = index;
		afterburner$builtVersion = version;
	}

	@Override
	public int afterburner$frameAt() {
		return afterburner$frameAt;
	}

	@Override
	public int afterburner$frameIndex() {
		return afterburner$frameIndex;
	}

	@Override
	public void afterburner$inFrame(int build, int index) {
		afterburner$frameAt = build;
		afterburner$frameIndex = index;
	}

	@Override
	public int afterburner$listedAt() {
		return afterburner$listedAt;
	}

	@Override
	public void afterburner$listed(int list) {
		afterburner$listedAt = list;
	}

	@Override
	public boolean afterburner$hasBlockEntities() {
		// Read before the mesh: one set after this changes the version again.
		int seen = afterburner$meshVersion + 1;
		if (seen != afterburner$entitiesSeen) {
			afterburner$hasEntities = !((SectionRenderDispatcher.RenderSection) (Object) this).getSectionMesh().getRenderableBlockEntities().isEmpty();
			afterburner$entitiesSeen = seen;
		}
		return afterburner$hasEntities;
	}
}
