///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;

public class Demo {
    static final String[] DIRECTIONS = {
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        try (var accelConn = new I2CConnection(bus, LSM303Full.I2C_ADDRESS_ACCEL);
             var magConn = new I2CConnection(bus, LSM303Full.I2C_ADDRESS_MAG)) {
            var compass = new LSM303Full(accelConn, magConn);   // Create LSM303 driver, (accel_connection, mag_connection)

            // --- Configure the sensor for a responsive compass ---
            // 50 Hz gives a fresh sample every 20 ms without flooding the FIFO, and
            // the high-resolution mode keeps 1 mg/LSB so the tilt compensation stays
            // precise.
            compass.setDataRate(50);                           // Set output data rate, (rate_hz=50) → None
            compass.setMode(LSM303Full.MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → None
            compass.setMagRange(130);                          // Set magnetometer range, (range_ut=130) → None
            compass.setMagDataRate(15);                        // Set magnetometer data rate, (rate_hz=15) → None

            // --- Arm the gestures ---
            // A double tap on the Z axis and a free-fall event both end up on INT1.
            // The latch keeps the line asserted until the source register is read, so
            // a short tap is never missed between two polling rounds.
            compass.setClick(0, 0x04, 0.8, 20, 40, 200);      // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → None
            compass.setFreeFall(1, 0.35, 60);                  // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=60) → None
            compass.setLatch(1, true);                         // Latch the interrupt, (generator=1, enabled=true) → None
            compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);   // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → None
            compass.enableInterrupt(LSM303Full.SOURCE_IA1, 1);     // Route an interrupt, (source=SOURCE_IA1=0x08, pin=1) → None

            // --- Collect the samples in the FIFO ---
            // Stream mode keeps the newest 32 sample sets; the watermark interrupts as
            // soon as 16 of them have piled up.
            compass.setFifo(LSM303Full.FIFO_STREAM, 16, false);  // Configure FIFO, (mode=FIFO_STREAM=2, watermark=16, trigger_int2=false) → None

            for (int round = 0; round < 60; round++) {
                // --- Average the samples the FIFO collected ---
                // Averaging over the whole burst cancels most of the vibration noise a
                // handheld compass would otherwise show.
                double ax = 0, ay = 0, az = 0;
                double[][] samples = compass.readFifo(8);     // Read FIFO, (count=8) → [[g, g, g], ...]
                if (samples.length > 0) {
                    for (double[] s : samples) {
                        ax += s[0];
                        ay += s[1];
                        az += s[2];
                    }
                    ax /= samples.length;
                    ay /= samples.length;
                    az /= samples.length;
                } else {
                    double[] a = compass.readAcceleration();   // Read acceleration, () → [g, g, g]
                    ax = a[0];
                    ay = a[1];
                    az = a[2];
                }

                // --- Compute the heading and the tilt ---
                double heading = compass.heading(true);         // Read the compass heading, (tilt_compensate=true) → °
                double roll = Math.toDegrees(Math.atan2(ay, az));
                double pitch = Math.toDegrees(Math.atan2(-ax, Math.sqrt(ay * ay + az * az)));
                double[] mag = compass.readMagnetic();          // Read magnetic field, () → [µT, µT, µT]
                double magnitude = Math.sqrt(mag[0] * mag[0] + mag[1] * mag[1] + mag[2] * mag[2]);
                double temperature = compass.readTemperatureDelta();  // Read relative temperature, () → °C
                int index = ((int) Math.floor((heading + 11.25) / 22.5)) % 16;
                if (index < 0) {
                    index += 16;
                }

                System.out.printf(
                        "heading %.1f deg (%s)  pitch %.1f deg  roll %.1f deg  field %.1f uT  temp %.1f C%n",
                        heading, DIRECTIONS[index], pitch, roll, magnitude, temperature);

                // --- Report the gestures ---
                int status = compass.pollInterrupt();           // Read interrupt status, () → bitmask
                if ((status & LSM303Full.SOURCE_CLICK) != 0) {
                    System.out.println("DOUBLE TAP");
                }
                if ((status & LSM303Full.SOURCE_IA1) != 0) {
                    System.out.println("FREE FALL");
                }

                Thread.sleep(1000);
            }
            System.out.println("done");
        }
    }
}
