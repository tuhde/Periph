///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Ad7706Full;
import it.uhde.periph.chips.adc_dac.Ad7706Minimal;

public class Ad7706Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 3, 1_000_000)) {
            var adcMin = new Ad7706Minimal(connection, 2.5f, Ad7706Minimal.MCLK_2_4576MHZ);
            var adc = new Ad7706Full(connection, 2.5f, Ad7706Full.MCLK_2_4576MHZ);

            int raw = adcMin.readRaw();
            checkTrue("Minimal.readRaw in [0, 65535]", raw >= 0 && raw <= 65535);

            float v = adcMin.readVoltage();
            checkTrue("Minimal.readVoltage in [-2.5, 2.5]", v >= -2.5f && v <= 2.5f);

            int raw1 = adc.readRaw(1);
            checkTrue("Full.readRaw(1) in [0, 65535]", raw1 >= 0 && raw1 <= 65535);
            float v1 = adc.readVoltage(1);
            checkTrue("Full.readVoltage(1) in [-2.5, 2.5]", v1 >= -2.5f && v1 <= 2.5f);

            int raw2 = adc.readRaw(2);
            checkTrue("Full.readRaw(2) in [0, 65535]", raw2 >= 0 && raw2 <= 65535);
            float v2 = adc.readVoltage(2);
            checkTrue("Full.readVoltage(2) in [-2.5, 2.5]", v2 >= -2.5f && v2 <= 2.5f);

            int raw3 = adc.readRaw(3);
            checkTrue("Full.readRaw(3) in [0, 65535]", raw3 >= 0 && raw3 <= 65535);
            float v3 = adc.readVoltage(3);
            checkTrue("Full.readVoltage(3) in [-2.5, 2.5]", v3 >= -2.5f && v3 <= 2.5f);

            adc.configure(1, Ad7706Full.GAIN_2, true, false, 60);
            checkTrue("configure(1, gain=2) accepted", true);
            adc.configure(2, Ad7706Full.GAIN_4, false, true, 60);
            checkTrue("configure(2, gain=4) accepted", true);
            adc.configure(3, Ad7706Full.GAIN_4, false, true, 60);
            checkTrue("configure(3, gain=4) accepted", true);
            adc.configure(1, Ad7706Full.GAIN_128, true, true, 50);
            checkTrue("configure(1, gain=128) accepted", true);

            adc.selfCalibrate(1);
            checkTrue("selfCalibrate(1) accepted", true);
            adc.selfCalibrate(2);
            checkTrue("selfCalibrate(2) accepted", true);
            adc.selfCalibrate(3);
            checkTrue("selfCalibrate(3) accepted", true);

            adc.systemCalibrateZero(1);
            checkTrue("systemCalibrateZero(1) accepted", true);
            adc.systemCalibrateFull(1);
            checkTrue("systemCalibrateFull(1) accepted", true);

            int off1 = adc.getOffsetCalibration(1);
            checkTrue("getOffsetCalibration(1) in [0, 2^24-1]", off1 >= 0 && off1 <= 0xFFFFFF);
            adc.setOffsetCalibration(off1, 1);
            checkTrue("setOffsetCalibration(1) accepted", true);

            int gain1 = adc.getGainCalibration(1);
            checkTrue("getGainCalibration(1) in [0, 2^24-1]", gain1 >= 0 && gain1 <= 0xFFFFFF);
            adc.setGainCalibration(gain1, 1);
            checkTrue("setGainCalibration(1) accepted", true);

            int off2 = adc.getOffsetCalibration(2);
            checkTrue("getOffsetCalibration(2) in [0, 2^24-1]", off2 >= 0 && off2 <= 0xFFFFFF);
            int gain2 = adc.getGainCalibration(2);
            checkTrue("getGainCalibration(2) in [0, 2^24-1]", gain2 >= 0 && gain2 <= 0xFFFFFF);

            int off3 = adc.getOffsetCalibration(3);
            checkTrue("getOffsetCalibration(3) in [0, 2^24-1]", off3 >= 0 && off3 <= 0xFFFFFF);
            int gain3 = adc.getGainCalibration(3);
            checkTrue("getGainCalibration(3) in [0, 2^24-1]", gain3 >= 0 && gain3 <= 0xFFFFFF);

            adc.standby();
            checkTrue("standby accepted", true);
            adc.wakeup();
            checkTrue("wakeup accepted", true);

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
            if (failed != 0) System.exit(1);
        }
    }
}
