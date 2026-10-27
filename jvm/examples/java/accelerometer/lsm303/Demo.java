///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;

public class Demo {

    private static final String[] COMPASS = {
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    static String compassName(double deg) {
        int idx = (int) Math.round(deg / 22.5) % 16;
        return COMPASS[idx];
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var accelConn = new I2CConnection(bus, 0x19);
             var magConn   = new I2CConnection(bus, 0x1E)) {

            // --- Configure: ±2 g, 50 Hz, high-resolution; magnetometer ±130 µT, 15 Hz ---
            // Tilt compensation needs a stable accelerometer, so the rate is kept moderate.
            var sensor = new LSM303Full(accelConn, magConn);       // Create LSM303 driver, (accelConnection, magConnection) → LSM303Full
            sensor.setRange(2);                                     // Set full-scale range, (rangeG=2) → None g
            sensor.setDataRate(50);                                 // Set accelerometer ODR, (rateHz=50) → None Hz
            sensor.setMode(2);                                      // Set accelerometer mode, (mode=2 high-resolution) → None
            sensor.setMagRange(130);                                // Set magnetometer range, (rangeUt=130) → None µT
            sensor.setMagDataRate(15);                              // Set magnetometer ODR, (rateHz=15) → None Hz

            // --- Arm tap and free-fall detection, routed to INT1 ---
            // A double tap on Z, and a free-fall condition latched until read.
            sensor.setClick(0, 0x04, 0.8, 20, 40, 200);             // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms) → None
            sensor.setFreeFall(1, 0.35, 60);                        // Configure free-fall, (generator, threshold_g, duration_ms) → None
            sensor.setLatch(1, true);                               // Latch generator 1, (generator, enabled) → None
            sensor.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);     // Route click to INT1, (source, pin=1) → None
            sensor.enableInterrupt(LSM303Full.SOURCE_IA1, 1);       // Route generator 1 to INT1, (source, pin=1) → None

            // --- FIFO stream mode, 16-sample watermark ---
            // The FIFO keeps the most recent samples between the one-second drains.
            sensor.setFifo(2, 16, false);                           // Configure FIFO, (mode=2 stream, watermark=16, triggerInt2=False) → None

            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end) {
                // --- Check interrupt sources ---
                // Reading the latched sources clears them, so each event prints once.
                int pending = sensor.pollInterrupt();               // Poll interrupt sources, () → int
                if ((pending & LSM303Full.SOURCE_CLICK) != 0) {
                    System.out.println("DOUBLE TAP");
                }
                if ((pending & LSM303Full.SOURCE_IA1) != 0) {
                    System.out.println("FREE FALL");
                }

                // --- Drain the FIFO and average the samples ---
                // Averaging the window smooths the 50 Hz noise before the tilt calculation.
                double[][] samples = sensor.readFifo();             // Read FIFO samples, () → list of [x, y, z] g
                double ax = 0, ay = 0, az = 0;
                for (double[] s : samples) {
                    ax += s[0];
                    ay += s[1];
                    az += s[2];
                }
                if (samples.length > 0) {
                    ax /= samples.length;
                    ay /= samples.length;
                    az /= samples.length;
                }

                // --- Read the magnetic field and the tilt-compensated heading ---
                // The heading uses both sensors, so the averaged gravity vector is not reused here.
                double[] m = sensor.readMagnetic();                 // Read magnetic field, () → double[3] µT
                double heading = sensor.heading(true);              // Compass heading (tilt-compensated), (tiltCompensate=True) → double degrees
                double fieldMag = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);

                // --- Pitch and roll from the averaged acceleration ---
                // Same convention as heading(): roll = atan2(ay, az), pitch from the normalised vector.
                double roll = Math.toDegrees(Math.atan2(ay, az));
                double pitch = Math.toDegrees(Math.atan2(-ax, Math.sqrt(ay * ay + az * az)));

                // --- Relative temperature ---
                // Delta only; the datasheet gives no absolute reference.
                double dT = sensor.readTemperatureDelta();          // Read temperature delta, () → double °C

                System.out.printf("heading %.1f° %s  pitch %.1f°  roll %.1f°  |B| %.1f µT  dT %+.2f °C%n",
                        heading, compassName(heading), pitch, roll, fieldMag, dT);
                Thread.sleep(1000);
            }
        }
    }
}
