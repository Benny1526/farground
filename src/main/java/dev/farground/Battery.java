package dev.farground;

import oshi.SystemInfo;
import oshi.hardware.PowerSource;

import java.util.List;

/**
 * Knows whether the computer is running on battery. Checked every 15 seconds on its own
 * background thread, because asking the operating system can take a moment.
 * Desktops (no battery) always count as plugged in.
 */
public final class Battery {
	private static volatile boolean onBattery;
	private static volatile boolean started;

	private Battery() {}

	public static boolean onBattery() {
		if (!started) start();
		return onBattery;
	}

	private static synchronized void start() {
		if (started) return;
		started = true;
		Thread t = new Thread(() -> {
			List<PowerSource> sources;
			try {
				sources = new SystemInfo().getHardware().getPowerSources();
			} catch (Throwable e) {
				Farground.LOG.info("Could not read power sources, battery pause disabled: {}", e.toString());
				return;
			}
			while (true) {
				try {
					boolean anyBattery = false, plugged = false;
					for (PowerSource ps : sources) {
						ps.updateAttributes();
						anyBattery = true;
						if (ps.isPowerOnLine()) plugged = true;
					}
					onBattery = anyBattery && !plugged;
				} catch (Throwable e) {
					onBattery = false;
				}
				try {
					Thread.sleep(15_000);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "Farground battery check");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}
}
