package com.afterburner.client.render;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import org.jspecify.annotations.Nullable;

/** A render pass that can be ended for a moment and go on afterwards with what it drew kept (see {@link OcclusionPortable}). */
public interface RestartablePass {
	/** What the pass was made with. */
	@Nullable RenderPassDescriptor afterburner$descriptor();

	void afterburner$setDescriptor(RenderPassDescriptor descriptor);

	/**
	 * Ends the pass, runs {@code between} (which may make and close passes of its own), and starts it again on the same
	 * attachments without clearing them, with the pipeline, uniforms and vertex buffers it had. Its index buffer must be
	 * set again. Returns false, running nothing, if the pass can't be ended here.
	 */
	boolean afterburner$restart(Runnable between);

	/**
	 * As {@link #afterburner$restart(Runnable)}, but going on with {@code next}'s attachments if it isn't null (none cleared),
	 * and with no pipeline bound then: the one bound before was made for the old attachments.
	 */
	boolean afterburner$restart(Runnable between, @Nullable RenderPassDescriptor next);
}
