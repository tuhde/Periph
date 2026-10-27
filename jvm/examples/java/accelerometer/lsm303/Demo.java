///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;
import it.uhde.periph.chips.accelerometer.LSM303Minimal;
import it.uhde.periph.chips.accelerometer.LSM303Source;

public class Demo {
    private static final String[] NAMES = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        try (var accelConnection = new I2CConnection(bus, 0x19);
             var magConnection = new I2CConnection(bus, 0x1E)) {
            var chip = new LSM303Full(accelConnection, magConnection);      // Create LSM303 Full driver, (accelConnection, magConnection) → void

            chip.setRange(2);                                              // Set accelerometer range, (rangeG) → void g
            chip.setMode(LSM303Minimal.MODE_HIGH_RESOLUTION);              // Set accelerometer mode, (mode) → void
            chip.setDataRate(50);                                          // Set accelerometer data rate, (rateHz) → void Hz
            chip.setMagRange(130);                                         // Set magnetometer range, (rangeUt) → void µT
            chip.setMagDataRate(15);                                       // Set magnetometer data rate, (rateHz) → void Hz
            chip.setClick(0, 0x04, 0.8, 20, 40, 200);                      // Configure click detection, (...) → void
            chip.setFreeFall(1, 0.35, 60);                                 // Configure free-fall, (...) → void
            chip.setLatch(1, true);                                        // Latch generator 1, (generator, enabled) → void
            chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 1);            // Route source to INT1, (source, pin) → void
            chip.enableInterrupt(LSM303Source.SOURCE_IA1, 1);              // Route source to INT1, (source, pin) → void
            chip.setFifo(LSM303Full.FIFO_STREAM, 16, false);               // Configure FIFO, (mode, watermark, triggerInt2) → void

            long start = System.currentTimeMillis();
            long last = start;
            while (System.currentTimeMillis() - start < 60000) {
                if (System.currentTimeMillis() - last >= 1000) {
                    int level = chip.fifoLevel();                          // Read FIFO level, () → int
                    double[][] samples = chip.readFifo(-1);                // Drain FIFO, (count) → double[][]
                    double[] a = chip.readAcceleration();                  // Read 3-axis acceleration, () → double[] g
                    double ax = a[0], ay = a[1], az = a[2];
                    if (samples.length > 0) {
                        double sx = 0, sy = 0, sz = 0;
                        for (double[] s : samples) { sx += s[0]; sy += s[1]; sz += s[2]; }
                        ax = sx / samples.length; ay = sy / samples.length; az = sz / samples.length;
                    }
                    double[] m = chip.readMagnetic();                      // Read 3-axis magnetic field, () → double[] µT
                    double field = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
                    double pitch = Math.atan2(ax, Math.sqrt(ay * ay + az * az)) * 180 / Math.PI;
                    double roll = Math.atan2(ay, az) * 180 / Math.PI;
                    double hdg = chip.heading(true);                       // Compute compass heading, (tiltCompensate) → double deg
                    String name = NAMES[(int) ((hdg + 11.25) / 22.5) % 16];
                    double temp = chip.readTemperatureDelta();             // Read relative temperature, () → double °C
                    System.out.printf("%5.1f° %s  pitch=%+.1f roll=%+.1f |B|=%.1f µT  T=%+.1f °C  fifo=%d%n",
                            hdg, name, pitch, roll, field, temp, level);
                    int isr = chip.pollInterrupt();                        // Read interrupt status, () → int
                    if ((isr & LSM303Source.SOURCE_CLICK) != 0) System.out.println("DOUBLE TAP");
                    if ((isr & LSM303Source.SOURCE_IA1) != 0) System.out.println("FREE FALL");
                    last = System.currentTimeMillis();
                }
                Thread.sleep(10);
            }
            System.out.println("done");
        }
    }
}
