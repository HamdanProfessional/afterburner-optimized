package com.afterburner.bench;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A player for benchmarks on a server nobody is on, so the server does what it does for a real player: loads and sends
 * the chunks around it, spawns mobs and random-ticks blocks near it, and sends it entity updates. It stands still and
 * answers the server like a client would (keep-alives, chunk batches, teleports), but whatever it's sent is dropped.
 */
public final class BenchPlayer {
	private static final List<BotConnection> BOTS = new CopyOnWriteArrayList<>();

	private BenchPlayer() {
	}

	public static void register() {
		// Vanilla ticks each player's connection after the worlds, which is where the player itself is ticked.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (BotConnection bot : BOTS) bot.tick();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> BOTS.clear());
	}

	public static ServerPlayer join(MinecraftServer server, ServerLevel level, Vec3 pos) {
		GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID("Benchmark"), "Benchmark");
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
		player.snapTo(pos, 0.0F, 0.0F);
		BotConnection connection = new BotConnection(player);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		BOTS.add(connection);
		return player;
	}

	private static final class BotConnection extends Connection {
		private static final SocketAddress ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
		private final ServerPlayer player;
		/** What the server sent that a client answers, answered on the next tick. */
		private final List<Packet<?>> toAnswer = new ArrayList<>();

		private BotConnection(ServerPlayer player) {
			super(PacketFlow.SERVERBOUND);
			this.player = player;
		}

		@Override
		public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
			if (packet instanceof ClientboundKeepAlivePacket || packet instanceof ClientboundChunkBatchFinishedPacket
					|| packet instanceof ClientboundPlayerPositionPacket) {
				synchronized (toAnswer) {
					toAnswer.add(packet);
				}
			}
		}

		@Override
		public void tick() {
			if (player.hasDisconnected()) return;
			List<Packet<?>> packets;
			synchronized (toAnswer) {
				packets = new ArrayList<>(toAnswer);
				toAnswer.clear();
			}
			for (Packet<?> packet : packets) {
				if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
					player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
				} else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
					player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64.0F));
				} else if (packet instanceof ClientboundPlayerPositionPacket teleport) {
					player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.id(),
							player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
				}
			}
			player.connection.tick();
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
		}

		@Override
		public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
		}

		@Override
		public void flushChannel() {
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public boolean isMemoryConnection() {
			// Like singleplayer: chunks are sent as fast as the server makes them.
			return true;
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return ADDRESS;
		}
	}
}
