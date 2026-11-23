package dev.farground;

/**
 * Whether the faster biome climate (26.3's BatchedClimate, for mod biome sources such as
 * lithostitched's) has been used this session. Chunks then cost much less to build, so a speed
 * tuned before it is too low; Generator tunes again once when it sees this.
 */
public final class FastBiomes {
	private FastBiomes() {}

	/** Set by the biome-source mixin the first time it batches a chunk's climate. */
	public static volatile boolean used;
}
