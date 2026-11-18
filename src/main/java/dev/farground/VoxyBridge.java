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
	 * Fast path: sky light is estimated inside the chunk instead of running Minecraft's light
	 * pass. Same rules as Minecraft's sky light: full light falls straight down through air,
	 * every other block it enters dims it (leaves, water and snow layers a little, solid blocks
	 * completely), and it then spreads sideways and down under overhangs, losing 1 per block.
	 * Only light from the neighbouring chunks is left out.
	 */
	private static boolean sendFast(ServerLevel level, ChunkAccess chunk, int cx, int cz) {
		WorldIdentifier id = WorldIdentifier.of(level);
		if (id == null) return false;
		WorldEngine engine = id.getOrCreateEngine();
		if (engine == null) return false;

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinSectionY();
		SkyEstimate sky = level.dimensionType().hasSkyLight() ? SkyEstimate.of(sections, minY) : null;

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null) continue;
			int sy = minY + i;
			DataLayer sl = sky == null ? null : sky.layer(sy * 16);
			if (!VoxelIngestService.rawIngest(engine, section.copy(), cx, sy, cz, null, sl)) {
				return false;
			}
		}
		return true;
	}

	/** Sky light worked out for one chunk. Package-private for tests. */
	static final class SkyEstimate {
		final int lo, hi;    // light[] covers lo..hi; everything above hi has full light, below lo none
		final byte[] light;  // index ((y - lo) << 8) | (z << 4) | x

		private SkyEstimate(int lo, int hi, byte[] light) {
			this.lo = lo;
			this.hi = hi;
			this.light = light;
		}

		static SkyEstimate of(LevelChunkSection[] sections, int minSectionY) {
			int bottom = minSectionY * 16;
			int top = bottom + sections.length * 16 - 1;

			// 1. Each column's highest block that stops light falling straight down.
			int[] colTop = new int[256];
			Arrays.fill(colTop, bottom - 1);
			int hi = bottom - 1;
			int remaining = 256;
			for (int i = sections.length - 1; i >= 0 && remaining > 0; i--) {
				LevelChunkSection s = sections[i];
				if (s == null || s.hasOnlyAir()) continue;
				int base = (minSectionY + i) * 16;
				for (int col = 0; col < 256; col++) {
					if (colTop[col] != bottom - 1) continue;
					int x = col & 15, z = col >> 4;
					for (int y = 15; y >= 0; y--) {
						if (!s.getBlockState(x, y, z).propagatesSkylightDown()) {
							colTop[col] = base + y;
							hi = Math.max(hi, base + y);
							remaining--;
							break;
						}
					}
				}
			}
			if (hi < bottom) return new SkyEstimate(bottom, bottom - 1, new byte[0]); // all open sky

			// 2. Follow the light down each column until it runs out.
			int[] colDark = new int[256];
			int lowestDark = hi;
			for (int col = 0; col < 256; col++) {
				int x = col & 15, z = col >> 4;
				int level = 15, y = colTop[col];
				while (y >= bottom && level > 0) {
					level -= Math.max(1, dampening(sections, minSectionY, x, y, z));
					if (level > 0) y--;
				}
				colDark[col] = y;
				lowestDark = Math.min(lowestDark, y);
			}
			// Light can spread up to 14 blocks below the darkest column, under overhangs.
			int lo = Math.max(bottom, lowestDark - 14);
			int height = hi - lo + 1;

			// 3. Light and dampening for every block in lo..hi.
			byte[] light = new byte[height << 8];
			byte[] damp = new byte[height << 8];
			for (int y = lo; y <= hi; y++) {
				int row = (y - lo) << 8;
				for (int col = 0; col < 256; col++) {
					damp[row | col] = (byte) dampening(sections, minSectionY, col & 15, y, col >> 4);
				}
			}
			for (int col = 0; col < 256; col++) {
				int level = 15;
				for (int y = hi; y >= lo; y--) {
					int idx = ((y - lo) << 8) | col;
					if (y <= colTop[col]) level = Math.max(0, level - Math.max(1, damp[idx]));
					if (level == 0) break;
					light[idx] = (byte) level;
				}
			}

			// 4. Spread sideways and down into the dark spots (under overhangs and trees).
			int[] queue = new int[light.length];
			int head = 0, tail = 0;
			for (int idx = 0; idx < light.length; idx++) {
				if (light[idx] > 1 && hasDarkerNeighbour(light, damp, idx, height)) queue[tail++] = idx;
			}
			while (head < tail) {
				int idx = queue[head++];
				int l = light[idx];
				int x = idx & 15, z = (idx >> 4) & 15, yi = idx >> 8;
				for (int d = 0; d < 6; d++) {
					int n = neighbour(idx, x, yi, z, d, height);
					if (n < 0 || damp[n] >= 15) continue;
					int nl = l - Math.max(1, damp[n]);
					if (nl > light[n]) {
						light[n] = (byte) nl;
						if (nl > 1) {
							if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length * 2);
							queue[tail++] = n;
						}
					}
				}
			}
			return new SkyEstimate(lo, hi, light);
		}

		private static boolean hasDarkerNeighbour(byte[] light, byte[] damp, int idx, int height) {
			int l = light[idx];
			int x = idx & 15, z = (idx >> 4) & 15, yi = idx >> 8;
			for (int d = 0; d < 6; d++) {
				int n = neighbour(idx, x, yi, z, d, height);
				if (n >= 0 && damp[n] < 15 && light[n] < l - Math.max(1, damp[n])) return true;
			}
			return false;
		}

		/** Index of the neighbour in direction d (0..5), or -1 outside the chunk or the range. */
		private static int neighbour(int idx, int x, int yi, int z, int d, int height) {
			return switch (d) {
				case 0 -> x > 0 ? idx - 1 : -1;
				case 1 -> x < 15 ? idx + 1 : -1;
				case 2 -> z > 0 ? idx - 16 : -1;
				case 3 -> z < 15 ? idx + 16 : -1;
				case 4 -> yi > 0 ? idx - 256 : -1;
				default -> yi < height - 1 ? idx + 256 : -1;
			};
		}

		private static int dampening(LevelChunkSection[] sections, int minSectionY, int x, int y, int z) {
			LevelChunkSection s = sections[(y >> 4) - minSectionY];
			if (s == null || s.hasOnlyAir()) return 0;
			var state = s.getBlockState(x, y & 15, z);
			return state.propagatesSkylightDown() ? 0 : Math.min(15, state.getLightDampening());
		}

		int get(int x, int y, int z) {
			if (y > hi) return 15;
			if (y < lo) return 0;
			return light[((y - lo) << 8) | (z << 4) | x];
		}

		/** Sky light for the section starting at height base: null when it's all dark. */
		DataLayer layer(int base) {
			if (base > hi) return FULL_SKY;
			if (base + 15 < lo) return null;
			DataLayer sl = new DataLayer();
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int l = get(x, base + y, z);
						if (l > 0) sl.set(x, y, z, l);
					}
				}
			}
			return sl;
		}
	}
}
