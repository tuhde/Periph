///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.motor.DRV2605Full;
import it.uhde.periph.chips.motor.DRV2605Minimal;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, DRV2605Minimal.DEFAULT_ADDRESS)) {  // open I²C bus, device address, (bus, address=0x5A) → I2CConnection
            var LRA = false;                                                                              // set to true for a linear resonant actuator
            var haptic = new DRV2605Full(connection, LRA);                                                // construct driver and run init, (connection, lra=false) → DRV2605Full

            // --- Verify and calibrate the actuator ---
            // diagnose() reports a missing or shorted actuator; calibrate() measures the
            // back-EMF so the closed-loop engine matches this particular motor.
            System.out.println("actuator ok: " + haptic.diagnose());                                      // Run actuator diagnostics, () → boolean
            var lraFreq = 0.0;
            if (LRA) {
                lraFreq = 175.0;
            }
            var cal = haptic.calibrate(LRA, 3.0, 3.0, lraFreq);                                           // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration(ok, comp, bemf, bemfGain)
            System.out.println("calibration: " + cal);

            // --- Play the three click effects ---
            for (int effect : new int[]{DRV2605Minimal.EFFECT_STRONG_CLICK, DRV2605Minimal.EFFECT_DOUBLE_CLICK, DRV2605Minimal.EFFECT_TRIPLE_CLICK}) {
                haptic.playEffect(effect);                                                                // Play a ROM effect, (effectId 1–123) → void
                haptic.waitDone(5000);                                                                    // Wait for playback, (timeoutMs) → boolean
                Thread.sleep(500);
            }

            // --- Heartbeat: two strong clicks 100 ms apart ---
            // The sequencer plays both effects and the delay without host involvement.
            haptic.playSequence(DRV2605Full.SequenceStep.effect(DRV2605Minimal.EFFECT_STRONG_CLICK), DRV2605Full.SequenceStep.delay(100), DRV2605Full.SequenceStep.effect(DRV2605Minimal.EFFECT_STRONG_CLICK));  // Play a sequence, (steps ≤ 8) → void
            haptic.waitDone(5000);                                                                        // Wait for playback, (timeoutMs) → boolean
            Thread.sleep(500);

            // --- Two-second amplitude ramp ---
            // Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
            // while the actuator is driven.
            for (int step = 0; step <= 20; step++) {
                haptic.rtpLevel(step / 20.0);                                                             // Stream an RTP amplitude, (level 0.0–1.0) → void
                Thread.sleep(100);
                System.out.println("vdd " + haptic.readSupplyVoltage() + " V");                           // Read supply voltage, () → double V
                if (LRA) {
                    System.out.println("lra " + haptic.readLraFrequency());                               // Read LRA frequency, () → OptionalDouble Hz (empty if LRA_PERIOD is 0)
                }
            }
            haptic.stop();                                                                                // Cancel playback, () → void

            // --- Report latched faults ---
            System.out.println("status: " + haptic.readStatus());                                         // Read status flags, () → Status(diagFailed, fbTimeout, overTemp, overCurrent)
        }
    }
}
