package dev.farground;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Far chunks whose saved copy is out of date and can be removed from disk.
 *
 * To build one far chunk, Minecraft first prepares the chunks up to 8 away, and saves them at that
 * early stage when they unload. Those early copies speed up the next rings, so they stay until
 * Farground has moved well past them; then they are only wasted space (about 4 KB each).
 *
 * The list survives restarts, so land finished just before closing the world is still cleaned up.
 */
final class StaleChunks {
	private static final int MAGIC = 0x46475354; // "FGST"
	/** Stages before any blocks exist. Nothing from a neighbour can be in a chunk this early. */
	private static final Set<String> EARLY = Set.of("empty", "structure_starts", "structure_references", "biomes");

	private final Path file;
	private final LongArrayList queue = new LongArrayList();
	private int head;
	private boolean dirty;

	/** Disk checks still running. */
	int reading;
	/** Saved copies removed this session. */
	long removed;

	StaleChunks(Path file) {
		this.file = file;
		load();
	}

	static boolean earlyStage(String status) {
		return EARLY.contains(status.startsWith("minecraft:") ? status.substring("minecraft:".length()) : status);
	}

	static int x(long key) { return (int) (key >> 32); }
	static int z(long key) { return (int) key; }

	void add(int cx, int cz) {
		queue.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
		dirty = true;
	}

	boolean isEmpty() {
		return head >= queue.size();
	}

	int size() {
		return queue.size() - head;
	}

	long peek() {
		return queue.getLong(head);
	}

	void pop() {
		head++;
		dirty = true;
		if (head >= queue.size()) {
			queue.clear();
			head = 0;
		} else if (head > 4096 && head > queue.size() / 2) {
			queue.removeElements(0, head);
			head = 0;
		}
	}

	private void load() {
		if (!Files.exists(file)) return;
		try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC) return;
			int n = in.readInt();
			for (int i = 0; i < n; i++) queue.add(in.readLong());
		} catch (IOException e) {
			// Only a cleanup list: losing it leaves some extra files on disk, nothing more.
			Farground.LOG.warn("Could not read {}", file, e);
		}
	}

	void saveIfDirty() {
		if (!dirty) return;
		try {
			if (isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
					out.writeInt(MAGIC);
					out.writeInt(size());
					for (int i = head; i < queue.size(); i++) out.writeLong(queue.getLong(i));
				}
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			dirty = false;
		} catch (IOException e) {
			Farground.LOG.warn("Could not save {}", file, e);
		}
	}
}
