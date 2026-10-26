package dev.farground;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

/**
 * Everything that talks to Voxy. Only loaded when Voxy is installed, so Farground also runs
 * without it (for example with only Distant Horizons).
 */
final class VoxyBridge {
	private VoxyBridge() {}

	private static final int QUEUE_LIMIT = 4096;

	/** null when Voxy is ready; otherwise what we are waiting for. */
	static String notReady() {
		var voxy = VoxyCommon.getInstance();
		if (voxy == null) return "waiting for Voxy to start";
		if (voxy.getIngestService().getTaskCount() > QUEUE_LIMIT) return "letting Voxy catch up";
		return null;
	}

	/** Is Voxy collecting LODs for this dimension? */
	static boolean enabledFor(ServerLevel level) {
		var voxy = VoxyCommon.getInstance();
		WorldIdentifier id = WorldIdentifier.of(level);
		return voxy != null && id != null && voxy.isIngestEnabled(id);
	}

	/** Hands a generated chunk to Voxy. realLight: use Minecraft's lighting, else work it out from the height. */
	static boolean send(ServerLevel level, ChunkAccess chunk, int cx, int cz, boolean realLight) {
		return realLight ? sendWithLight(level, chunk, cx, cz) : sendFast(level, chunk, cx, cz);
	}

	private static boolean sendWithLight(ServerLevel level, ChunkAccess chunk, int cx, int cz) {
		WorldIdentifier id = WorldIdentifier.of(level);
		if (id == null) return false;
		WorldEngine engine = id.getOrCreateEngine();
		if (engine == null) return false;

		var light = level.getLightEngine();
		var blockLight = light.getLayerListener(LightLayer.BLOCK);
		var skyLight = light.getLayerListener(LightLayer.SKY);

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinSectionY();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null) continue;
			int sy = minY + i;
			SectionPos pos = SectionPos.of(cx, sy, cz);
			DataLayer bl = blockLight.getDataLayerData(pos);
			DataLayer sl = skyLight.getDataLayerData(pos);
			// Copies, because Voxy reads them later on its own threads.
			if (!VoxelIngestService.rawIngest(engine, section.copy(), cx, sy, cz,
					bl == null ? null : bl.copy(), sl == null ? null : sl.copy())) {
				return false;
			}
		}
		return true;
	}

	// Backed by a real array: a DataLayer built from just a default value reports isEmpty(),
	// which Voxy would read as "no light".
	private static final DataLayer FULL_SKY = fullSky();

	private static DataLayer fullSky() {
		byte[] bytes = new byte[2048];
		Arrays.fill(bytes, (byte) 0xFF);
		return new DataLayer(bytes);
	}

	/**
	 * Fast path: sky light is worked out from the terrain height instead of Minecraft's light
	 * pass. Everything above the highest solid block in a column gets full sky light.
	 */
	private static boolean sendFast(ServerLevel level, ChunkAccess chunk, int cx, int cz) {
		WorldIdentifier id = WorldIdentifier.of(level);
		if (id == null) return false;
		WorldEngine engine = id.getOrCreateEngine();
		if (engine == null) return false;

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinSectionY();
		boolean hasSky = level.dimensionType().hasSkyLight();

		int[] top = new int[256];
		int minTop = Integer.MAX_VALUE, maxTop = Integer.MIN_VALUE;
		if (hasSky) {
			int bottom = minY * 16 - 1;
			Arrays.fill(top, bottom);
			int remaining = 256;
			for (int i = sections.length - 1; i >= 0 && remaining > 0; i--) {
				LevelChunkSection s = sections[i];
				if (s == null || s.hasOnlyAir()) continue;
				int base = (minY + i) * 16;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int col = z * 16 + x;
						if (top[col] != bottom) continue;
						for (int y = 15; y >= 0; y--) {
							// Sky light passes through see-through blocks (water, glass, leaves).
							if (s.getBlockState(x, y, z).canOcclude()) {
								top[col] = base + y;
								remaining--;
								break;
							}
						}
					}
				}
			}
			for (int t : top) {
				minTop = Math.min(minTop, t);
				maxTop = Math.max(maxTop, t);
			}
		}

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null) continue;
			int sy = minY + i;
			int base = sy * 16;
			DataLayer sl = null;
			if (hasSky) {
				if (base > maxTop) {
					sl = FULL_SKY;
				} else if (base + 15 > minTop) {
					sl = new DataLayer();
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int from = Math.max(0, top[z * 16 + x] - base + 1);
							for (int y = from; y < 16; y++) sl.set(x, y, z, 15);
						}
					}
				}
			}
			if (!VoxelIngestService.rawIngest(engine, section.copy(), cx, sy, cz, null, sl)) {
				return false;
			}
		}
		return true;
	}
}
