///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9930Full;

public class Demo {
    static final float DIM_LUX_THRESHOLD = 10.0f;
    static final int   PROX_SCREEN_OFF  = 400;

    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x39)) {            // Open I2C connection, (bus=1, address=0x39) → I2CConnection
            var apds = new Apds9930Full(connection);                     // Create APDS-9930 Full driver, (connection) → Apds9930Full
                                                                       // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive
            Thread.sleep(110);

            // --- Sample lux and proximity once per second for 30 cycles ---
            // The user is encouraged to cover the sensor with a hand (proximity
            // rises) and to dim/undim the room light to watch both action lines
            // fire.
            for (int i = 0; i < 30; i++) {
                Thread.sleep(1000);
                float lx = apds.lux();                                  // Read ambient illuminance, () → float lx
                int p = apds.proximity();                                // Read proximity count, () → int count
                System.out.printf("lux=%.1f lx  proximity=%d%n", lx, p);
                if (lx < DIM_LUX_THRESHOLD) System.out.println("  -> dim backlight");
                if (p > PROX_SCREEN_OFF)     System.out.println("  -> disable screen");
            }
        }
    }
}