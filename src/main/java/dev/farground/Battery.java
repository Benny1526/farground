package dev.farground;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import oshi.SystemInfo;
import oshi.hardware.PowerSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Knows whether the computer is running on battery. Checked every 15 seconds on its own
 * background thread, because asking the operating system can take a moment.
 *
 * Only the computer's own battery counts. Desktops, UPS units and the batteries in wireless
 * mice, keyboards and headsets never pause Farground. When in doubt, it counts as plugged in.
 *  - Windows: the system power status (the same answer the taskbar battery icon shows).
 *  - Linux: /sys/class/power_supply, ignoring device batteries (scope "Device").
 *  - macOS: the internal battery reported by the system.
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
			BooleanSupplier check;
			try {
				check = pickCheck();
			} catch (Throwable e) {
				check = null;
			}
			if (check == null) {
				Farground.LOG.info("Could not read the battery state, battery pause disabled");
				return;
			}
			while (true) {
				try {
					onBattery = check.getAsBoolean();
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

	private static BooleanSupplier pickCheck() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			Kernel32Power k = Native.load("kernel32", Kernel32Power.class);
			return () -> {
				SystemPowerStatus s = new SystemPowerStatus();
				if (!k.GetSystemPowerStatus(s)) return false;
				return windowsOnBattery(s.ACLineStatus & 0xFF, s.BatteryFlag & 0xFF);
			};
		}
		if (os.contains("linux")) {
			Path root = Path.of("/sys/class/power_supply");
			return () -> linuxOnBattery(root);
		}
		List<PowerSource> sources = new SystemInfo().getHardware().getPowerSources();
		return () -> {
			boolean internal = false, plugged = false;
			for (PowerSource ps : sources) {
				ps.updateAttributes();
				String name = (ps.getName() + " " + ps.getDeviceName()).toLowerCase(Locale.ROOT);
				if (name.contains("ups")) continue; // a backup power unit, not a laptop battery
				internal = true;
				if (ps.isPowerOnLine() || ps.isCharging()) plugged = true;
			}
			return internal && !plugged;
		};
	}

	/**
	 * Windows: on battery only when the power line is reported offline (0) and a system battery
	 * exists. Battery flag 128 means "no system battery" and 255 means "unknown".
	 */
	static boolean windowsOnBattery(int acLineStatus, int batteryFlag) {
		if (batteryFlag == 128 || batteryFlag == 255) return false;
		return acLineStatus == 0;
	}

	/**
	 * Linux: on battery only when a system battery is discharging and no mains or USB power
	 * supply is online. Wireless mice, keyboards and headsets (scope "Device") are ignored.
	 */
	static boolean linuxOnBattery(Path root) {
		boolean discharging = false;
		try (var dirs = Files.list(root)) {
			for (Path d : (Iterable<Path>) dirs::iterator) {
				String type = read(d.resolve("type"));
				if (type.equalsIgnoreCase("Battery")) {
					if (read(d.resolve("scope")).equalsIgnoreCase("Device")) continue;
					if (read(d.resolve("status")).equalsIgnoreCase("Discharging")) discharging = true;
				} else if (read(d.resolve("online")).equals("1")) {
					return false; // mains, USB-C or other charger plugged in
				}
			}
		} catch (Exception e) {
			return false;
		}
		return discharging;
	}

	private static String read(Path p) {
		try {
			return Files.readString(p).trim();
		} catch (Exception e) {
			return "";
		}
	}

	/** kernel32 GetSystemPowerStatus. */
	public interface Kernel32Power extends StdCallLibrary {
		boolean GetSystemPowerStatus(SystemPowerStatus status);
	}

	@Structure.FieldOrder({"ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag",
			"BatteryLifeTime", "BatteryFullLifeTime"})
	public static class SystemPowerStatus extends Structure {
		public byte ACLineStatus;
		public byte BatteryFlag;
		public byte BatteryLifePercent;
		public byte SystemStatusFlag;
		public int BatteryLifeTime;
		public int BatteryFullLifeTime;
	}
}
