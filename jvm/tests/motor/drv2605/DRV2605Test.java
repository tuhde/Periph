///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.motor.DRV2605Minimal;
import it.uhde.periph.chips.motor.DRV2605Full;

public class DRV2605Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x5A"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new DRV2605Minimal(connection);
            checkTrue(true, "construct_minimal");
            minimal.playEffect(DRV2605Minimal.EFFECT_STRONG_CLICK);
            Thread.sleep(500);
            checkTrue(!minimal.isPlaying(), "effect_finishes");

            var haptic = new DRV2605Full(connection);
            checkTrue(haptic.readDeviceId() == 3, "device_id");
            haptic.playSequence(DRV2605Full.SequenceStep.effect(1), DRV2605Full.SequenceStep.delay(100),
                    DRV2605Full.SequenceStep.effect(1));
            checkTrue(haptic.waitDone(3000), "sequence_finishes");

            haptic.rtpLevel(0.5);
            Thread.sleep(100);
            double vdd = haptic.readSupplyVoltage();
            checkTrue(vdd > 2.0 && vdd < 6.0, "supply_voltage_plausible");
            haptic.stop();
            checkTrue(!haptic.isPlaying(), "stop_clears_go");

            haptic.diagnose();
            checkTrue(true, "diagnose_runs");
            var status = haptic.readStatus();
            checkTrue(!status.overTemp(), "no_overtemp");
            checkTrue(!status.overCurrent(), "no_overcurrent");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
