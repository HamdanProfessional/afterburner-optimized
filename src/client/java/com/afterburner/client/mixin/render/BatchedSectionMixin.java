package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BatchedBuffer;
import com.afterburner.client.render.BatchedSection;
import com.afterburner.client.render.ChunkBatcher;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Where a section's layers are in their regions ({@link ChunkBatcher}), let go of whenever vanilla resets the section:
 * when it moves to another place, or its buffers are let go of. Its compiled state is then empty, so nothing of it is drawn.
 * <p>
 * The versions count mesh and compiled changes per layer, and the frame stamps say what the batcher's frames last saw,
 * so a frame is only looked up again where something actually changed.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class BatchedSectionMixin implements BatchedSection {
	/** 1: empty for the layer, 2: not empty; 0: not looked at since the compiled state last changed. */
	@Unique
	private static final byte AFTERBURNER$UNKNOWN = 0, AFTERBURNER$EMPTY = 1, AFTERBURNER$PRESENT = 2;

	@Shadow
	@Final
	private Map<RenderType, VertexBuffer> buffers;
	/**
	 * 1.21.1's constructor calls {@code reset()} (through {@code setOrigin}) before Mixin inserts @Unique field
	 * initialisers, so these are made in the TAIL of {@code <init>} instead and every reader guards for null.
	 */
	@Unique
	private ChunkBatcher.SectionMesh[] afterburner$meshes;
	/** Per layer: mesh changes (any thread), the frame build that last looked it up, its place there, and the mesh version seen. */
	@Unique
	private AtomicIntegerArray afterburner$versions;
	@Unique
	private int[] afterburner$builtVersions, afterburner$frameAt, afterburner$frameIndex;
	/** The shared visible-list stamp the batcher's once-per-frame diff last put the section in. */
	@Unique
	private int afterburner$listStamp;
	/** The compiled state the emptiness cache below was made from, and per layer whether that state is empty for it. */
	@Unique
	private SectionRenderDispatcher.CompiledSection afterburner$compiledSeen;
	@Unique
	private byte[] afterburner$empty;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void afterburner$tagBuffers(CallbackInfo ci) {
		int layers = ChunkBatcher.LAYERS.size();
		afterburner$meshes = new ChunkBatcher.SectionMesh[layers];
		afterburner$versions = new AtomicIntegerArray(layers);
		afterburner$builtVersions = new int[layers];
		afterburner$frameAt = new int[layers];
		afterburner$frameIndex = new int[layers];
		afterburner$empty = new byte[layers];
		for (Map.Entry<RenderType, VertexBuffer> entry : buffers.entrySet()) {
			((BatchedBuffer) entry.getValue()).afterburner$tag((SectionRenderDispatcher.RenderSection) (Object) this,
					ChunkBatcher.LAYERS.indexOf(entry.getKey()));
		}
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void afterburner$release(CallbackInfo ci) {
		if (afterburner$meshes == null) return;
		int released = ChunkBatcher.release(afterburner$meshes);
		if (released == 0) return;
		for (int layer = 0; layer < afterburner$meshes.length; layer++) {
			if ((released >> layer & 1) != 0) afterburner$meshChanged(layer);
		}
		// The next frame takes the section in in place ({@link ChunkBatcher#changed}).
		ChunkBatcher.changed((SectionRenderDispatcher.RenderSection) (Object) this);
	}

	/**
	 * A build that found the section's neighbours missing leaves it empty ({@code EMPTY}) without uploading anything: what
	 * an older build stored for it must not be drawn any more. Once it's set: a frame built between the two could still
	 * have seen the old state, and the version left lower than it makes the next one look the section up again.
	 */
	@Inject(method = "setCompiled", at = @At("RETURN"))
	private void afterburner$compiled(SectionRenderDispatcher.CompiledSection compiled, CallbackInfo ci) {
		if (afterburner$meshes == null) return;
		boolean changed = false;
		for (int layer = 0; layer < afterburner$meshes.length; layer++) {
			if (afterburner$meshes[layer] != null && compiled.isEmpty(ChunkBatcher.LAYERS.get(layer))) {
				afterburner$meshChanged(layer);
				changed = true;
			}
		}
		if (changed) ChunkBatcher.changed((SectionRenderDispatcher.RenderSection) (Object) this);
	}

	@Override
	public ChunkBatcher.SectionMesh[] afterburner$meshes() {
		// Before the constructor's TAIL made it (nothing can have stored a mesh yet), made here so a caller can't see null.
		ChunkBatcher.SectionMesh[] meshes = afterburner$meshes;
		if (meshes == null) afterburner$meshes = meshes = new ChunkBatcher.SectionMesh[ChunkBatcher.LAYERS.size()];
		return meshes;
	}

	@Override
	public void afterburner$meshChanged(int layer) {
		if (afterburner$versions == null) return;
		afterburner$versions.incrementAndGet(layer);
	}

	@Override
	public int afterburner$meshVersion(int layer) {
		return afterburner$versions == null ? 0 : afterburner$versions.get(layer);
	}

	@Override
	public int afterburner$builtVersion(int layer) {
		return afterburner$builtVersions == null ? 0 : afterburner$builtVersions[layer];
	}

	@Override
	public void afterburner$built(int layer, int version) {
		if (afterburner$builtVersions == null) return;
		afterburner$builtVersions[layer] = version;
	}

	@Override
	public int afterburner$frameAt(int layer) {
		return afterburner$frameAt == null ? 0 : afterburner$frameAt[layer];
	}

	@Override
	public int afterburner$frameIndex(int layer) {
		return afterburner$frameIndex == null ? -1 : afterburner$frameIndex[layer];
	}

	@Override
	public void afterburner$inFrame(int layer, int stamp, int index) {
		if (afterburner$frameAt == null) return;
		afterburner$frameAt[layer] = stamp;
		afterburner$frameIndex[layer] = index;
	}

	@Override
	public int afterburner$listStamp() {
		return afterburner$listStamp;
	}

	@Override
	public void afterburner$listStamp(int stamp) {
		afterburner$listStamp = stamp;
	}

	@Override
	public boolean afterburner$isEmpty(int layer) {
		SectionRenderDispatcher.CompiledSection compiled = ((SectionRenderDispatcher.RenderSection) (Object) this).getCompiled();
		if (compiled != afterburner$compiledSeen) {
			// A new compiled state (or the first look): every layer's answer is stale.
			afterburner$compiledSeen = compiled;
			byte[] empty = afterburner$empty;
			if (empty == null) afterburner$empty = empty = new byte[ChunkBatcher.LAYERS.size()];
			else Arrays.fill(empty, AFTERBURNER$UNKNOWN);
		}
		byte[] empty = afterburner$empty;
		if (empty == null || layer < 0 || layer >= empty.length) return compiled.isEmpty(ChunkBatcher.LAYERS.get(layer));
		byte cached = empty[layer];
		if (cached != AFTERBURNER$UNKNOWN) return cached == AFTERBURNER$EMPTY;
		boolean isEmpty = compiled.isEmpty(ChunkBatcher.LAYERS.get(layer));
		empty[layer] = isEmpty ? AFTERBURNER$EMPTY : AFTERBURNER$PRESENT;
		return isEmpty;
	}
}
