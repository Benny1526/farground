package dev.farground;

/**
 * What the generator may ask the game client about. Kept as an interface so the generator
 * itself never touches client-only classes (needed for dedicated-server use).
 */
public interface ClientSignals {
	/** Is the game window focused and not paused? (Frame rate is meaningless otherwise.) */
	boolean active();

	/** Is the game window focused at all? (Unfocused = you are away.) */
	boolean focused();

	/** Frames drawn in the last second. */
	int fps();

	/** Was there a stutter (a few very slow frames) in the last couple of seconds? */
	boolean stutter();

	/**
	 * Distance in chunks beyond which Voxy draws blocks coarser than the given size, so trees
	 * are no longer worth generating. -1 if unknown.
	 */
	int autoFullDetailChunks(int voxelSize);

	/** How far the installed LOD mod draws, in chunks (the larger if both are installed). -1 if unknown. */
	default int lodRenderDistanceChunks() {
		return -1;
	}
}
