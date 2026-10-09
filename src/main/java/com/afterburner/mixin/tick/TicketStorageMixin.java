package com.afterburner.mixin.tick;

import com.afterburner.tick.TickingSections;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Every tick each dimension asks whether any chunk ticket keeps it running. With one player there's one such ticket (at the
 * player's own chunk) among a thousand or more, and vanilla walks the tickets until it comes to it. This looks at the chunk
 * where it was found last tick first, and walks the tickets only if it's gone from there. The answer is the same.
 */
@Mixin(TicketStorage.class)
public abstract class TicketStorageMixin {
	@Shadow
	@Final
	private Long2ObjectOpenHashMap<List<Ticket>> tickets;
	@Unique
	private long afterburner$keeper;

	@Inject(method = "shouldKeepDimensionActive", at = @At("HEAD"), cancellable = true)
	private void afterburner$keepActive(CallbackInfoReturnable<Boolean> cir) {
		boolean keep = afterburner$keeps(tickets.get(afterburner$keeper));
		if (!keep) {
			for (Long2ObjectMap.Entry<List<Ticket>> entry : Long2ObjectMaps.fastIterable(tickets)) {
				if (afterburner$keeps(entry.getValue())) {
					afterburner$keeper = entry.getLongKey();
					keep = true;
					break;
				}
			}
		}
		if (TickingSections.CHECK) {
			boolean vanilla = false;
			for (List<Ticket> group : tickets.values()) vanilla |= afterburner$keeps(group);
			TickingSections.checkKeepActive(keep, vanilla);
		}
		cir.setReturnValue(keep);
	}

	@Unique
	private static boolean afterburner$keeps(@Nullable List<Ticket> group) {
		if (group == null) return false;
		for (Ticket ticket : group) {
			if (ticket.getType().shouldKeepDimensionActive()) return true;
		}
		return false;
	}
}
