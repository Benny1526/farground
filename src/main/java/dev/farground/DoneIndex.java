package dev.farground;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Remembers which chunks were already sent to Voxy, so nothing is generated twice,
 * even after a restart. One bit per chunk, grouped by 32x32 region: 128 bytes per region.
 *
 * Two layers: "full" chunks were generated with trees, ores and structures; "terrain"
 * chunks only got the land shape (used for far-away rings). A terrain-only chunk is
 * upgraded to full if you later come close enough for full detail.
 */
public final class DoneIndex {
	private static final int MAGIC = 0x46475244; // "FGRD"
	private static final int FORMAT = 2;

	private final Path file;
	private final Long2ObjectOpenHashMap<long[]> full = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<long[]> terrain = new Long2ObjectOpenHashMap<>();
	private boolean dirty;
	private long fullCount, terrainCount;

	public DoneIndex(Path file) {
		this.file = file;
		load();
	}

	private static long regionKey(int cx, int cz) {
		return ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
	}

	private static int bit(int cx, int cz) {
		return ((cz & 31) << 5) | (cx & 31);
	}

	private static boolean has(Long2ObjectOpenHashMap<long[]> map, int cx, int cz) {
		long[] bits = map.get(regionKey(cx, cz));
		if (bits == null) return false;
		int b = bit(cx, cz);
		return (bits[b >>> 6] & (1L << b)) != 0;
	}

	private static boolean set(Long2ObjectOpenHashMap<long[]> map, int cx, int cz) {
		long[] bits = map.computeIfAbsent(regionKey(cx, cz), k -> new long[16]);
		int b = bit(cx, cz);
		long mask = 1L << b;
		if ((bits[b >>> 6] & mask) != 0) return false;
		bits[b >>> 6] |= mask;
		return true;
	}

	/** Already done at the needed quality? A full chunk satisfies a terrain-only request too. */
	public boolean isDone(int cx, int cz, boolean needFull) {
		if (has(full, cx, cz)) return true;
		return !needFull && has(terrain, cx, cz);
	}

	public void markDone(int cx, int cz, boolean isFull) {
		if (isFull) {
			if (set(full, cx, cz)) { fullCount++; dirty = true; }
		} else if (!has(full, cx, cz)) {
			if (set(terrain, cx, cz)) { terrainCount++; dirty = true; }
		}
	}

	/** Chunks done (either quality) anywhere in this dimension. */
	public long count() {
		return fullCount + terrainCount;
	}

	/** Chunks done (either quality) inside the square [x0..x1] x [z0..z1]. */
	public long countInSquare(int x0, int z0, int x1, int z1) {
		long n = 0;
		for (int rx = x0 >> 5; rx <= x1 >> 5; rx++) {
			for (int rz = z0 >> 5; rz <= z1 >> 5; rz++) {
				long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
				long[] f = full.get(key), t = terrain.get(key);
				if (f == null && t == null) continue;
				int bx = rx << 5, bz = rz << 5;
				boolean inside = bx >= x0 && bx + 31 <= x1 && bz >= z0 && bz + 31 <= z1;
				if (inside) {
					for (int i = 0; i < 16; i++) {
						long v = (f == null ? 0 : f[i]) | (t == null ? 0 : t[i]);
						n += Long.bitCount(v);
					}
				} else {
					for (int lz = 0; lz < 32; lz++) {
						int z = bz + lz;
						if (z < z0 || z > z1) continue;
						for (int lx = 0; lx < 32; lx++) {
							int x = bx + lx;
							if (x < x0 || x > x1) continue;
							int b = (lz << 5) | lx;
							long m = 1L << b;
							if ((f != null && (f[b >>> 6] & m) != 0) || (t != null && (t[b >>> 6] & m) != 0)) n++;
						}
					}
				}
			}
		}
		return n;
	}

	public void clear() {
		full.clear();
		terrain.clear();
		fullCount = terrainCount = 0;
		dirty = true;
	}

	private void load() {
		if (!Files.exists(file)) return;
		try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC) {
				Farground.LOG.warn("Ignoring unknown progress file {}", file);
				return;
			}
			int format = in.readInt();
			if (format != 1 && format != 2) {
				Farground.LOG.warn("Ignoring progress file {} with unknown format {}", file, format);
				return;
			}
			fullCount = readMap(in, full);
			if (format >= 2) terrainCount = readMap(in, terrain); // format 1 only had full chunks
		} catch (IOException e) {
			Farground.LOG.warn("Could not read progress file {}, starting fresh", file, e);
			clear();
			dirty = false;
		}
	}

	private static long readMap(DataInputStream in, Long2ObjectOpenHashMap<long[]> map) throws IOException {
		long count = 0;
		int n = in.readInt();
		for (int i = 0; i < n; i++) {
			long key = in.readLong();
			long[] bits = new long[16];
			for (int j = 0; j < 16; j++) {
				bits[j] = in.readLong();
				count += Long.bitCount(bits[j]);
			}
			map.put(key, bits);
		}
		return count;
	}

	private static void writeMap(DataOutputStream out, Long2ObjectOpenHashMap<long[]> map) throws IOException {
		out.writeInt(map.size());
		for (Long2ObjectMap.Entry<long[]> e : map.long2ObjectEntrySet()) {
			out.writeLong(e.getLongKey());
			for (long l : e.getValue()) out.writeLong(l);
		}
	}

	public void saveIfDirty() {
		if (!dirty) return;
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
				out.writeInt(MAGIC);
				out.writeInt(FORMAT);
				writeMap(out, full);
				writeMap(out, terrain);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			Farground.LOG.warn("Could not save progress file {}", file, e);
		}
	}
}
