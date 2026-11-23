package dev.farground;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * /farground prune: frees disk space by deleting saved chunk data for land you have never been
 * near. Voxy keeps its LODs, and Farground remembers the chunks as done, so the distant view
 * does not change. If you ever travel there, Minecraft simply generates the land again.
 *
 * Safety rules:
 *  - Runs only after the world has fully closed and saved (never while chunks are in use).
 *  - Deletes only chunks that have just the land shape (generated no further than the terrain
 *    step). Minecraft's terrain step writes into its own chunk only, so such a chunk is
 *    re-created exactly the same. Chunks with trees, plants or structures placed, and every
 *    chunk a player has been near, are never touched.
 *  - Also keeps any land-shape chunk next to a decorated one: trees spill into the chunks around
 *    them, and those parts would be lost.
 *  - Keeps oversized chunks stored in separate .mcc files, and anything it can't read.
 *  - Copies every region file it changes to "farground-prune-backup" first.
 */
public final class Pruner {
	private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int SECTOR = 4096;

	public record Result(int filesChanged, long chunksRemoved, long bytesBefore, long bytesAfter, Path backup) {}

	private Pruner() {}

	public static Result prune(Path worldRoot, List<Path> regionDirs) throws IOException {
		Path backupRoot = worldRoot.resolve("farground-prune-backup");
		// Pass 1: find every chunk that has been decorated (or can't be read) across all region files.
		LongOpenHashSet decorated = new LongOpenHashSet();
		List<Path> files = new ArrayList<>();
		for (Path dir : regionDirs) {
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(p -> REGION.matcher(p.getFileName().toString()).matches()).forEach(files::add);
			}
		}
		for (Path f : files) {
			int[] rc = regionCoords(f);
			byte[] data = Files.readAllBytes(f);
			for (int i = 0; i < 1024; i++) {
				String status = status(data, i);
				if (status == null) continue;
				if (!landShapeOnly(status)) {
					decorated.add(pack(rc[0] * 32 + (i & 31), rc[1] * 32 + (i >> 5)));
				}
			}
		}

		// Pass 2: rewrite each region file without the chunks that are safe to drop.
		int changed = 0;
		long removed = 0, before = 0, after = 0;
		for (Path f : files) {
			int[] rc = regionCoords(f);
			byte[] data = Files.readAllBytes(f);
			before += data.length;
			boolean[] drop = new boolean[1024];
			int dropCount = 0;
			for (int i = 0; i < 1024; i++) {
				String status = status(data, i);
				if (status == null || !landShapeOnly(status)) continue;
				int cx = rc[0] * 32 + (i & 31), cz = rc[1] * 32 + (i >> 5);
				if (near(decorated, cx, cz, 1)) continue;
				drop[i] = true;
				dropCount++;
			}
			if (dropCount == 0) {
				after += data.length;
				continue;
			}
			Path rel = worldRoot.relativize(f);
			Path backup = backupRoot.resolve(rel.toString());
			Files.createDirectories(backup.getParent());
			Files.copy(f, backup, StandardCopyOption.REPLACE_EXISTING);

			byte[] out = rewrite(data, drop);
			Path tmp = f.resolveSibling(f.getFileName() + ".farground.tmp");
			Files.write(tmp, out);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			after += out.length;
			changed++;
			removed += dropCount;
		}
		return new Result(changed, removed, before, after, backupRoot);
	}

	public record Compacted(int files, long bytesBefore, long bytesAfter) {}

	/**
	 * Closes the gaps left in region files by removed chunks (Minecraft reuses the gaps but never
	 * shrinks a file). Every chunk is copied unchanged, so no backup is needed. Files with anything
	 * odd in their table are left alone. Run only after the world has closed. Stops after maxMillis;
	 * what is left gets its turn the next time.
	 */
	public static Compacted compact(List<Path> regionDirs, long maxMillis) throws IOException {
		long deadline = System.nanoTime() + maxMillis * 1_000_000L;
		int changed = 0;
		long before = 0, after = 0;
		for (Path dir : regionDirs) {
			if (!Files.isDirectory(dir)) continue;
			List<Path> files;
			try (Stream<Path> s = Files.list(dir)) {
				files = s.filter(p -> REGION.matcher(p.getFileName().toString()).matches()).toList();
			}
			for (Path f : files) {
				if (System.nanoTime() > deadline) return new Compacted(changed, before, after);
				long size = Files.size(f);
				if (!worthCompacting(f, size)) continue;
				byte[] out = rewrite(Files.readAllBytes(f), new boolean[1024]);
				Path tmp = f.resolveSibling(f.getFileName() + ".farground.tmp");
				Files.write(tmp, out);
				try {
					Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
				}
				changed++;
				before += size;
				after += out.length;
			}
		}
		return new Compacted(changed, before, after);
	}

	/** True if at least a quarter of the file (and 256 KB) is unused and its table looks sound. */
	private static boolean worthCompacting(Path f, long size) throws IOException {
		if (size < 2L * SECTOR) return false;
		ByteBuffer header = ByteBuffer.allocate(SECTOR);
		try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
			while (header.hasRemaining() && ch.read(header, header.position()) > 0) { }
		}
		if (header.hasRemaining()) return false;
		long fileSectors = (size + SECTOR - 1) / SECTOR, used = 2;
		for (int i = 0; i < 1024; i++) {
			int loc = header.getInt(i * 4);
			if (loc == 0) continue;
			int offset = loc >>> 8, count = loc & 0xFF;
			if (offset < 2 || count == 0 || offset + (long) count > fileSectors) return false;
			used += count;
		}
		long free = fileSectors - used;
		return free >= 64 && free * 4 >= fileSectors; // a negative "free" means overlapping chunks
	}

	/** Statuses up to and including the terrain step (26.2 calls its last ones noise/surface/carvers). */
	private static final java.util.Set<String> LAND_SHAPE = java.util.Set.of(
			"empty", "structure_starts", "structure_references", "biomes",
			"noise", "surface", "carvers", "terrain");

	/** True for a chunk generated no further than the land shape, so nothing spilled out of it. */
	static boolean landShapeOnly(String status) {
		String s = status.startsWith("minecraft:") ? status.substring("minecraft:".length()) : status;
		return LAND_SHAPE.contains(s);
	}

	private static boolean near(LongOpenHashSet set, int cx, int cz, int r) {
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				if (set.contains(pack(cx + dx, cz + dz))) return true;
		return false;
	}

	private static long pack(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static int[] regionCoords(Path f) {
		Matcher m = REGION.matcher(f.getFileName().toString());
		if (!m.matches()) throw new IllegalArgumentException(f.toString());
		return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
	}

	/** The chunk's generation status, "EXTERNAL" for chunks in .mcc files, or null if absent/unreadable. */
	private static String status(byte[] file, int index) {
		if (file.length < 2 * SECTOR) return null;
		ByteBuffer b = ByteBuffer.wrap(file);
		int loc = b.getInt(index * 4);
		if (loc == 0) return null;
		int offset = (loc >>> 8) * SECTOR;
		if (offset + 5 > file.length) return null;
		int length = b.getInt(offset);
		int type = file[offset + 4] & 0xFF;
		if ((type & 0x80) != 0) return "EXTERNAL";
		if (length <= 1 || offset + 4 + length > file.length) return null;
		try {
			RegionFileVersion v = RegionFileVersion.fromId(type);
			if (v == null) return null;
			try (InputStream in = v.wrap(new ByteArrayInputStream(file, offset + 5, length - 1));
			     DataInputStream din = new DataInputStream(in)) {
				CompoundTag tag = NbtIo.read(din, NbtAccounter.unlimitedHeap());
				return tag.getStringOr("Status", "minecraft:full");
			}
		} catch (Exception e) {
			return "EXTERNAL"; // unreadable: treat as untouchable
		}
	}

	/** Builds a compact region file containing every chunk except the dropped ones. */
	private static byte[] rewrite(byte[] in, boolean[] drop) {
		ByteBuffer src = ByteBuffer.wrap(in);
		List<byte[]> payloads = new ArrayList<>();
		int[] keepIndex = new int[1024];
		int[] timestamps = new int[1024];
		int totalSectors = 2;
		int[] sectorsOf = new int[1024];
		for (int i = 0; i < 1024; i++) {
			keepIndex[i] = -1;
			int loc = src.getInt(i * 4);
			if (loc == 0 || drop[i]) continue;
			int offset = (loc >>> 8) * SECTOR;
			int count = loc & 0xFF;
			int bytes = Math.min(count * SECTOR, in.length - offset);
			byte[] p = new byte[count * SECTOR];
			System.arraycopy(in, offset, p, 0, Math.max(0, bytes));
			keepIndex[i] = payloads.size();
			payloads.add(p);
			sectorsOf[i] = count;
			timestamps[i] = src.getInt(SECTOR + i * 4);
			totalSectors += count;
		}
		ByteBuffer out = ByteBuffer.allocate(totalSectors * SECTOR);
		int next = 2;
		for (int i = 0; i < 1024; i++) {
			if (keepIndex[i] < 0) continue;
			out.putInt(i * 4, (next << 8) | (sectorsOf[i] & 0xFF));
			out.putInt(SECTOR + i * 4, timestamps[i]);
			out.put(next * SECTOR, payloads.get(keepIndex[i]));
			next += sectorsOf[i];
		}
		return out.array();
	}
}
