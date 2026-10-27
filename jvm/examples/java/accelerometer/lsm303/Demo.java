///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;

import java.util.List;

public class Demo {

    private static final String[] COMPASS_POINTS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        try (var accelConn = new I2CConnection(bus, 0x19);
             var magConn   = new I2CConnection(bus, 0x1E)) {
            var compass = new LSM303Full(accelConn, magConn);
                                                                  // Create LSM303 Full driver, (accel_connection, mag_connection)

            // --- Configure the e-compass: ±2 g at 50 Hz, ±130 µT at 15 Hz ---
            // High-resolution mode keeps 1 mg/LSB tilt resolution; the 50 Hz
            // accelerometer rate gives the tap / free-fall detectors a 20 ms
            // time base (1 LSb = 1/ODR).
            compass.setDataRate(50);                              // Set accelerometer data rate, (rateHz) → Hz
            compass.setMagRange(130);                             // Set magnetometer range, (rangeUt) → µT

            // --- Arm double tap on Z and a latched free-fall generator ---
            // The double tap needs a short time limit and a wide window;
            // free-fall is latched so a brief drop is never missed between polls.
            compass.setClick(0, 0x04, 0.8, 20, 40, 200);          // Configure click detection, (single=0, double=0x04, thresholdG, timeLimitMs, latencyMs, windowMs) → g, ms
            compass.setFreeFall(1, 0.35, 60);                    // Configure free-fall, (generator=1, thresholdG, durationMs) → g, ms
            compass.setLatch(1, true);                            // Set interrupt latching, (generator, enabled) → None
            compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1); // Route interrupt source, (source, pin) → None
            compass.enableInterrupt(LSM303Full.SOURCE_IA1, 1);   // Route interrupt source, (source, pin) → None

            // --- Stream-mode FIFO with a 16-sample watermark for clean averaging ---
            compass.setFifo(2, 16, false);                       // Configure FIFO, (mode, watermark, triggerInt2) → None

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 60_000) {
                List<double[]> samples = compass.readFifo(-1);   // Drain the FIFO, (count=-1 all unread) → [g, g, g]*
                double ax = 0, ay = 0, az = 0;
                if (!samples.isEmpty()) {
                    for (double[] s : samples) {                  // average the drained sample sets
                        ax += s[0]; ay += s[1]; az += s[2];
                    }
                    int n = samples.size();
                    ax /= n; ay /= n; az /= n;
                }
                double[] m = compass.readMagnetic();              // Read 3-axis magnetic field, () → [µT, µT, µT]
                double hdg = compass.heading(true);               // Compute compass heading, (tiltCompensate=true) → float °
                double field = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
                String name = COMPASS_POINTS[(int) ((hdg + 11.25) / 22.5) % 16];
                double temp = compass.readTemperatureDelta();     // Read relative temperature, () → float °C
                if (!samples.isEmpty()) {
                    double pitch = Math.toDegrees(Math.atan2(-ax, Math.sqrt(ay * ay + az * az)));
                    double roll = Math.toDegrees(Math.atan2(ay, az));
                    System.out.printf("%5.1f deg %s  pitch=%+.1f roll=%+.1f  |m|=%.0f uT  dT=%+.1f C%n",
                            hdg, name, pitch, roll, field, temp);
                } else {
                    System.out.printf("%5.1f deg %s  |m|=%.0f uT  dT=%+.1f C%n",
                            hdg, name, field, temp);
                }

                int status = compass.pollInterrupt();             // Poll all enabled interrupt sources, () → int
                if ((status & LSM303Full.SOURCE_CLICK) != 0) {
                    System.out.println("DOUBLE TAP");
                }
                if ((status & LSM303Full.SOURCE_IA1) != 0) {
                    System.out.println("FREE FALL");
                }

                Thread.sleep(1000);
            }
        }
    }
}
