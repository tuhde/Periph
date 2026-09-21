///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Ad7705Full;

public class Demo {
    static final float TEMP_COEFF = 0.05f;
    static final float TEMP_REFERENCE = 1.25f;
    static final float CHANGE_THRESHOLD = 0.001f;

    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0, 3, 5_000_000)) {   // Open SPI bus 0, CS 0, Mode 3, 5 MHz, (bus, device, mode=3, maxSpeedHz=5_000_000) → SPIConnection
            var adc = new Ad7705Full(connection, 2.5f, Ad7705Full.MCLK_2_4576MHZ);    // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → Ad7705Full

            // --- Configure both channels for the bridge-pressure application ---
            adc.configure(1, Ad7705Full.GAIN_128, true, true, 50);                   // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
            adc.configure(2, Ad7705Full.GAIN_2, true, false, 50);                    // Configure channel 2, (channel=2, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=50) → None

            // --- Self-calibrate both channels before the measurement loop ---
            adc.selfCalibrate(1);                                                    // Self-calibrate channel, (channel=1) → None
            adc.selfCalibrate(2);                                                    // Self-calibrate channel, (channel=2) → None

            Float lastPressure = null;
            while (true) {
                float pressureRaw = adc.readVoltage(1);                              // Read voltage on channel 1, (channel=1) → float V
                float temp = adc.readVoltage(2);                                      // Read voltage on channel 2, (channel=2) → float V
                float pressure = pressureRaw - TEMP_COEFF * (temp - TEMP_REFERENCE);
                if (lastPressure == null || Math.abs(pressure - lastPressure) > CHANGE_THRESHOLD) {
                    System.out.printf("→ pressure=%.4f V (raw %.4f V, temp %.4f V)%n", pressure, pressureRaw, temp);
                    lastPressure = pressure;
                }
                Thread.sleep(200);
            }
        }
    }
}
