///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.magnetometer.As5600Full;

/**
 * Motor feedback monitor: reads angle, raw count, and AGC 10 times per second.
 * Warns when AGC drifts outside the optimal range (magnet too close or too far).
 * Detects magnet insertion and removal via STATUS changes.
 */
public class Demo {

    private static final int SAMPLES     = 10;
    private static final long INTERVAL_MS = 100;

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x36)) {            // open I²C bus 1, device 0x36, (bus, address) → I2CTransport
            var as5600 = new As5600Full(transport);                        // construct driver, (transport) → As5600Full

            // --- Configure for responsive angle tracking ---
            // Normal power mode with 16× slow filter gives 2.2 ms settling;
            // no hysteresis for smooth tracking in a motor feedback loop.
            as5600.configure(                                        // configure chip, (pm=0, hyst=0, outs=0, pwmf=0, sf=0, fth=0, wd=false) → void
                    As5600Full.PM_NOM, As5600Full.HYST_OFF,
                    As5600Full.OUTS_ANALOG, As5600Full.PWMF_115HZ,
                    As5600Full.SF_16X, 0, false);

            boolean prevMd = true;

            for (int i = 0; i < SAMPLES; i++) {
                double angle = as5600.angle();                       // read angle in degrees, () → double deg
                int raw = as5600.angleRaw();                         // read raw angle count, () → int 0–4095
                int agc = as5600.agc();                              // read AGC value, () → int
                int status = as5600.statusByte();                    // read raw STATUS byte, () → int

                boolean md = (status & 0x08) != 0;
                boolean mh = (status & 0x20) != 0;
                boolean ml = (status & 0x10) != 0;

                // --- Detect magnet state changes ---
                // STATUS.MD transitions indicate magnet insertion or removal.
                if (md != prevMd) {
                    if (md) {
                        System.out.printf("[MAGNET DETECTED] MD=1  MH=%d  ML=%d%n", mh ? 1 : 0, ml ? 1 : 0);
                    } else {
                        System.out.println("[MAGNET REMOVED] MD=0");
                    }
                    prevMd = md;
                }

                if (!md) {
                    Thread.sleep(INTERVAL_MS);
                    continue;
                }

                // --- Assess AGC health ---
                // In 5 V mode, AGC mid-range ≈ 128 is optimal.
                // AGC < 64 suggests magnet too close/strong; AGC > 192 suggests too far/weak.
                String agcStatus = "[OK]";
                if (agc < 64 || agc > 192) {
                    agcStatus = "[AGC low — magnet weak or too far]";
                }
                if (mh) agcStatus = "[AGC high — magnet too strong or too close]";

                System.out.printf("angle=%.2f°  raw=%d  agc=%d  %s%n", angle, raw, agc, agcStatus);

                Thread.sleep(INTERVAL_MS);
            }
        }
    }
}
