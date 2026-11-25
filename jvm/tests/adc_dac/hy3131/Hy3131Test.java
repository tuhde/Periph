///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Hy3131Full;
import it.uhde.periph.chips.adc_dac.Hy3131Minimal;

public class Hy3131Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 1_000_000)) {
            var adcMin = new Hy3131Minimal(connection, 1.2f);
            var adc = new Hy3131Full(connection, 1.2f);

            int raw = adcMin.readRaw();
            checkTrue("Minimal.readRaw in 24-bit range", raw >= -8388608 && raw <= 8388607);
            checkTrue("Minimal.readVoltage finite", Float.isFinite(adcMin.readVoltage()));
            adcMin.dataReady();
            checkTrue("Minimal.dataReady accepted", true);

            adc.configureAd1(4, 2, 0, 0, 0, 0);
            checkTrue("Full.readRaw after configureAd1", adc.readRaw() >= -8388608);
            adc.setAd1Input(10, 3, 2);
            adc.setAd1Reference(1, 1);
            checkTrue("setAd1Input/Reference accepted", true);

            int tcode = adc.readTemperatureCode();
            checkTrue("readTemperature finite", Float.isFinite(adc.readTemperature(tcode)));

            adc.enableAd2(5, 1, 0, false, false, 0, false);
            int ad2 = adc.readAd2Raw();
            checkTrue("readAd2Raw in 19-bit range", ad2 >= -262144 && ad2 <= 262143);
            adc.enableLpf(0);
            adc.readLpfRaw();
            checkTrue("readLpfRaw accepted", true);
            adc.enableRms();
            checkTrue("readRms >= 0", adc.readRms() >= 0f);
            adc.enablePeakHold(0);
            int[] peak = adc.readPeak();
            checkTrue("readPeak min <= max", peak[0] <= peak[1]);
            adc.enableInterrupts(0x04);
            adc.readFlags();
            checkTrue("readFlags accepted", true);
            adc.reset();
            checkTrue("reset accepted", true);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
