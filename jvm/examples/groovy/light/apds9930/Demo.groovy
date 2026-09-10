///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9930Full

final float DIM_LUX_THRESHOLD = 10.0f
final int   PROX_SCREEN_OFF   = 400

I2CConnection conn = new I2CConnection(1, 0x39)                          // Open I2C connection, (bus=1, address=0x39) → I2CConnection
try {
    def apds = new Apds9930Full(conn)                                     // Create APDS-9930 Full driver, (connection) → Apds9930Full
                                                                           // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive
    Thread.sleep(110)
    // --- Sample lux and proximity once per second for 30 cycles ---
    // The user is encouraged to cover the sensor with a hand (proximity
    // rises) and to dim/undim the room light to watch both action lines
    // fire.
    30.times {
        Thread.sleep(1000)
        float lx = apds.lux()                                             // Read ambient illuminance, () → float lx
                                                                           // IR-compensated lux via Ch0/Ch1 difference
        int p = apds.proximity()                                          // Read proximity count, () → int count
                                                                           // 16-bit ADC value; higher = closer
        printf("lux=%.1f lx  proximity=%d%n", lx, p)
        if (lx < DIM_LUX_THRESHOLD) println("  -> dim backlight")
        if (p > PROX_SCREEN_OFF)     println("  -> disable screen")
    }
} finally {
    conn.close()
}