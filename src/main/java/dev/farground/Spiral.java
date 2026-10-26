package dev.farground;

import java.util.Arrays;
import java.util.function.DoubleSupplier;

/**
 * Walks chunks outward from a center, nearest first, skipping the inner radius.
 *
 * Chunks are visited in square tiles (TILE x TILE chunks), with the tiles themselves walked in
 * square rings. Inside a tile, chunks share neighbors, and Minecraft has to partly generate a
 * chunk's neighbors before it can finish it; working tile by tile reuses that work.
 *
 * Within each ring, the tiles in the direction you are looking come first, so the horizon in
 * front of you fills in before the land behind you.
 */
public final class Spiral {
	public static final int TILE = 8;

	public final int centerX, centerZ;
	private final int innerRadius, maxRadius;
	private final int maxTileRing;
	private final DoubleSupplier yaw; // degrees, Minecraft convention; NaN or null = no preference

	private int tileRing;     // ring index of the current tile around the center tile
	private int tileStep;     // index into the ring's visiting order
	private int[] order;      // ring positions in visiting order (look direction first)
	private int inTile;       // 0 .. TILE*TILE-1 within the current tile
	private int tileX, tileZ;
	private boolean finished;

	public Spiral(int centerX, int centerZ, int innerRadius, int maxRadius) {
		this(centerX, centerZ, innerRadius, maxRadius, null);
	}

	public Spiral(int centerX, int centerZ, int innerRadius, int maxRadius, DoubleSupplier yaw) {
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.innerRadius = Math.max(0, innerRadius);
		this.maxRadius = maxRadius;
		this.maxTileRing = (maxRadius + TILE) / TILE + 1;
		this.yaw = yaw;
		this.tileRing = Math.max(0, this.innerRadius / TILE - 1);
		this.tileStep = 0;
		startRing();
		setTile();
	}

	public boolean finished() {
		return finished;
	}

	/** Roughly how far out it has got, in chunks. */
	public int radius() {
		return Math.min(maxRadius, tileRing * TILE);
	}

	/** Writes the next chunk into out[0], out[1]. Returns false when everything is done. */
	public boolean next(int[] out) {
		while (!finished) {
			int lx = inTile % TILE, lz = inTile / TILE;
			int x = tileX + lx, z = tileZ + lz;
			if (++inTile >= TILE * TILE) {
				inTile = 0;
				advanceTile();
			}
			int d = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
			if (d >= innerRadius && d <= maxRadius) {
				out[0] = x;
				out[1] = z;
				return true;
			}
		}
		return false;
	}

	private void advanceTile() {
		int ringSize = tileRing == 0 ? 1 : 8 * tileRing;
		if (++tileStep >= ringSize) {
			tileStep = 0;
			tileRing++;
			if (tileRing > maxTileRing) {
				finished = true;
				return;
			}
			startRing();
		}
		setTile();
	}

	/** Decide the visiting order for the new ring: closest to the look direction first. */
	private void startRing() {
		int r = tileRing;
		int size = r == 0 ? 1 : 8 * r;
		order = new int[size];
		for (int i = 0; i < size; i++) order[i] = i;
		double y = yaw == null ? Double.NaN : yaw.getAsDouble();
		if (r == 0 || Double.isNaN(y)) return;
		// Minecraft yaw: 0 = +Z (south), 90 = -X (west).
		double rad = Math.toRadians(y);
		double dx = -Math.sin(rad), dz = Math.cos(rad);
		double[] score = new double[size];
		int[] pos = new int[2];
		for (int i = 0; i < size; i++) {
			ringPos(r, i, pos);
			double len = Math.hypot(pos[0], pos[1]);
			score[i] = -(pos[0] * dx + pos[1] * dz) / len; // lower = more in front
		}
		Integer[] boxed = new Integer[size];
		for (int i = 0; i < size; i++) boxed[i] = i;
		Arrays.sort(boxed, (a, b) -> Double.compare(score[a], score[b]));
		for (int i = 0; i < size; i++) order[i] = boxed[i];
	}

	/** Tile offset (in tiles) of position s on ring r. */
	private static void ringPos(int r, int s, int[] out) {
		if (r == 0) { out[0] = 0; out[1] = 0; return; }
		int side = 2 * r;
		if (s < side) { out[0] = -r + s; out[1] = -r; }
		else if (s < 2 * side) { out[0] = r; out[1] = -r + (s - side); }
		else if (s < 3 * side) { out[0] = r - (s - 2 * side); out[1] = r; }
		else { out[0] = -r; out[1] = r - (s - 3 * side); }
	}

	private final int[] tmp = new int[2];

	/** Sets tileX/tileZ (chunk coords of the tile's corner) from tileRing and the visiting order. */
	private void setTile() {
		ringPos(tileRing, order[tileStep], tmp);
		// Tile (0,0) is centered on the player's chunk.
		tileX = centerX - TILE / 2 + tmp[0] * TILE;
		tileZ = centerZ - TILE / 2 + tmp[1] * TILE;
	}
}
