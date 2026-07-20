///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.light.Apds9960Full;

/**
 * Ambient light monitoring demo with adaptive integration time.
 *
 * Monitors ambient light in a loop, printing RGBC channels and computed
 * approximate lux. When the clear channel approaches saturation, the
 * integration time is halved to prevent overflow.
 */
public class Demo {

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x39)) {            // open I²C bus 1, device 0x39, (bus, address) → I2CTransport
            var apds = new Apds9960Full(transport);                        // construct driver, (transport) → Apds9960Full

            // --- Monitor ambient light with adaptive integration time ---
            // Start with the default 200 ms integration (ATIME=0xB6). When the clear
            // channel approaches saturation, halve the integration time to prevent overflow.
            int atime = 0xB6;
            apds.configureAls(atime, 1);                                   // configure ALS, (atime 0-255, again 0-3) → void

            for (int i = 0; i < 20; i++) {
                while (!apds.isAlsValid()) {                               // check ALS data valid, () → boolean
                    Thread.sleep(10);
                }

                int[] rgbc = apds.color();                                 // read all RGBC channels, () → int[] [clear, red, green, blue]
                int c = rgbc[0], r = rgbc[1], g = rgbc[2], b = rgbc[3];
                double lux = -0.32466 * r + 1.57837 * g + -0.73191 * b;
                System.out.printf("C=%d R=%d G=%d B=%d  lux~%.0f%n", c, r, g, b, lux);

                // --- Adaptive integration: reduce time when saturated ---
                if (apds.isAlsSaturated() && atime < 0xFE) {               // check ALS saturated, () → boolean
                    atime = atime + (256 - atime) / 2;
                    if (atime > 0xFE) atime = 0xFE;
                    apds.configureAls(atime, 1);                           // configure ALS, (atime 0-255, again 0-3) → void
                    System.out.printf("[SATURATED — reducing integration time, ATIME=0x%02X]%n", atime);
                    Thread.sleep(250);
                }

                Thread.sleep(1000);
            }
        }
    }
}
