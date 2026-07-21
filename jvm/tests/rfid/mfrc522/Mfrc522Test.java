///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.rfid.Mfrc522Full;

public class Mfrc522Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x28").replaceFirst("^0[xX]", ""), 16);

        try (var transport = new I2CTransport(bus, addr)) {
            var mfrc = new Mfrc522Full(transport, Mfrc522Full.BUS_I2C);

            int[] v = mfrc.version();
            checkTrue("chipType == 0x09 (MFRC522)", v[0] == 0x09);
            checkTrue("version in {1, 2}", v[1] == 1 || v[1] == 2);

            mfrc.antennaOn();
            int[] gains = {18, 23, 33, 38, 43, 48};
            for (int dB : gains) {
                mfrc.setAntennaGain(dB);
                checkTrue("setAntennaGain(" + dB + ") read back", mfrc.antennaGain() == dB);
            }

            mfrc.reset();
            checkTrue("reset accepted", true);

            mfrc.antennaOff();
            checkTrue("antennaOff accepted", true);
        }
        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
