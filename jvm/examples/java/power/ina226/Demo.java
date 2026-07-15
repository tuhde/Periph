///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina226Full;

/**
 * Power-supply monitoring demo: poll voltage, current, and power once per second
 * for 10 seconds, track min/max/mean, and demonstrate a power over-limit alert.
 *
 * Between samples 4 and 5 the user is prompted to switch on a load so that the
 * effect of load variation is visible in the statistics. The power over-limit
 * alert is set to 1 W; alertFlags() is checked on every iteration.
 */
public class Demo {

    private static final int    SAMPLES      = 10;
    private static final long   INTERVAL_MS  = 1000;
    private static final double ALERT_POWER  = 1.0;  // W

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x40)) {            // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            var ina = new Ina226Full(transport, 0.1, 2.0);                 // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Full

            // --- Configure for noise-sensitive power rail monitoring ---
            // 128-sample averaging suppresses switching noise on a noisy supply;
            // continuous mode avoids re-triggering overhead between measurements.
            ina.configure(Ina226Full.AVG_128, Ina226Full.CT_1100US,        // configure ADC, (avg=4, vbusCt=4, vshCt=4, mode=7) → void
                          Ina226Full.CT_1100US, Ina226Full.MODE_SHUNT_BUS_CONT);

            // --- Arm a power over-limit alert at 1 W ---
            // The POL function asserts the ALERT pin and sets the OVF latch when
            // the calculated power register exceeds the threshold. We poll alertFlags()
            // on each iteration instead of wiring a GPIO interrupt.
            ina.setAlert(Ina226Full.POL, ALERT_POWER);                     // set power over-limit alert, (function=POL, limit=1.0 W) → void

            double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE, sumV = 0;
            double minI = Double.MAX_VALUE, maxI = -Double.MAX_VALUE, sumI = 0;
            double minP = Double.MAX_VALUE, maxP = -Double.MAX_VALUE, sumP = 0;

            for (int i = 0; i < SAMPLES; i++) {
                if (i == 4) {
                    System.out.println("Switch on load now...");
                }

                double v = ina.voltage();      // read bus voltage, () → double V
                double c = ina.current();      // read current, () → double A
                double p = ina.power();        // read power, () → double W

                int alertRaw = ina.alertFlags();  // read alert flags (clears latch), () → int

                boolean alert = (alertRaw & Ina226Full.POL) != 0;
                System.out.printf("Sample %2d: V=%.3f V  I=%.4f A  P=%.4f W%s%n",
                        i + 1, v, c, p, alert ? "  [ALERT: power over limit]" : "");

                minV = Math.min(minV, v); maxV = Math.max(maxV, v); sumV += v;
                minI = Math.min(minI, c); maxI = Math.max(maxI, c); sumI += c;
                minP = Math.min(minP, p); maxP = Math.max(maxP, p); sumP += p;

                Thread.sleep(INTERVAL_MS);
            }

            // --- Print summary statistics ---
            // Min/max/mean over the 10-second window gives a compact view of
            // supply stability and load variation.
            System.out.println();
            System.out.printf("Bus voltage  — min=%.3f V   max=%.3f V   mean=%.3f V%n",
                    minV, maxV, sumV / SAMPLES);
            System.out.printf("Current      — min=%.4f A  max=%.4f A  mean=%.4f A%n",
                    minI, maxI, sumI / SAMPLES);
            System.out.printf("Power        — min=%.4f W  max=%.4f W  mean=%.4f W%n",
                    minP, maxP, sumP / SAMPLES);

        }
    }
}
