package dev.farground;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Faster biomes for modded biome sources.
 *
 * <p>Since 26.3, Minecraft fills a chunk's biomes from a resolver made for the whole chunk
 * ({@code BiomeSource.createResolverForChunk}). Vanilla's multi-noise source works out the six
 * climate values for the chunk in one batch there. A biome source from a mod that only has the
 * older per-point {@code createResolver} (for example lithostitched's biome injector, which many
 * worldgen mods use) falls back to sampling the climate one point at a time: 1,536 separate
 * samples per chunk in a vanilla-height world, 2,304 with Lithosphere, each re-running every
 * climate formula. With heavy terrain mods that is most of the time spent on a chunk.
 *
 * <p>This gives such a source a climate sampler that answers from a batch computed the vanilla
 * way the first time it is asked for a point in the chunk. Points outside the chunk's 4-block
 * biome grid are sampled normally. The biome source itself is unchanged and gets the same
 * climate values, so the biomes are the same (checked by Farground's biome test).
 */
public final class BatchedClimate {
	private BatchedClimate() {}

	/** Whether to batch for this biome source: only mod sources that rely on the per-point fallback. */
	public static boolean appliesTo(BiomeSource source) {
		FargroundConfig cfg = Farground.config();
		if (cfg != null && !cfg.fastModdedBiomes) return false;
		// Vanilla's own sources either batch already (multi-noise) or don't sample the climate per point.
		return !source.getClass().getName().startsWith("net.minecraft.");
	}

	/** A climate sampler for one chunk's biome grid, as passed to {@code createResolverForChunk}. */
	public static Climate.Sampler forChunk(Climate.Sampler s, int quartX, int quartY, int quartZ, int sizeX, int sizeY, int sizeZ) {
		// The same grid vanilla's multi-noise source uses for the chunk.
		DensityVolume volume = new DensityVolume(sizeX, sizeY, sizeZ,
				QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ), 4, 4, 4);
		return new Climate.Sampler(batched(s.temperature(), volume), batched(s.humidity(), volume),
				batched(s.continentalness(), volume), batched(s.erosion(), volume),
				batched(s.depth(), volume), batched(s.weirdness(), volume));
	}

	private static DensitySampler.Bound batched(DensitySampler.Bound source, DensityVolume volume) {
		return new DensitySampler.Bound(new Batch(source, volume), source.context());
	}

	/** One climate value over the chunk's biome grid, computed on first use. Used by one chunk on one thread. */
	private static final class Batch implements DensitySampler {
		private final DensitySampler.Bound source;
		private final DensityVolume volume;
		private DensityBuffer values;

		Batch(DensitySampler.Bound source, DensityVolume volume) {
			this.source = source;
			this.volume = volume;
		}

		@Override
		public float sampleValue(SamplerContext context, int x, int y, int z) {
			int i = volume.indexOfBlock(x, y, z);
			if (i < 0) return source.sampleValue(x, y, z);
			DensityBuffer v = values;
			if (v == null) {
				v = DensityBuffer.createUnpooled(volume.size());
				source.sampleVolume(v, volume);
				values = v;
			}
			return v.get(i);
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer out, DensityVolume v) {
			source.sampleVolume(out, v);
		}
	}
}
