///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Ad7705Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0, 3, 5_000_000)) {   // Open SPI bus 0, CS 0, Mode 3, 5 MHz, (bus, device, mode=3, maxSpeedHz=5_000_000) → SPIConnection
            var adc = new Ad7705Full(connection, 2.5f, Ad7705Full.MCLK_2_4576MHZ);    // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → Ad7705Full
                                                                                     // constructor runs the Initialization Sequence (gain 1, bipolar, 50 Hz, self-calibrate Channel 1)

            adc.configure(2, Ad7705Full.GAIN_8, true, true, 60);                      // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                                     // sets gain/bipolar/buffered/output_rate for the given channel; does not calibrate
            adc.selfCalibrate(2);                                                    // Self-calibrate channel, (channel=2) → None
                                                                                     // runs internal self-calibration, blocking until DRDY

            int off2 = adc.getOffsetCalibration(2);                                  // Read offset calibration, (channel=2) → int 24-bit
            int gain2 = adc.getGainCalibration(2);                                   // Read gain calibration, (channel=2) → int 24-bit
            System.out.printf("ch2 offset=%d gain=%d%n", off2, gain2);

            int raw1 = adc.readRaw(1);                                               // Read raw 16-bit code, (channel=1) → int 16-bit
                                                                                     // blocks until DRDY, returns raw Data Register code
            float v1 = adc.readVoltage(1);                                           // Read voltage, (channel=1) → float V
                                                                                     // converts raw code to volts using channel's current gain/bipolar
            float v2 = adc.readVoltage(2);                                           // Read voltage, (channel=2) → float V
            System.out.printf("ch1 raw=%d ch1 v=%.4f ch2 v=%.4f%n", raw1, v1, v2);

            adc.standby();                                                           // Enter standby, () → None
                                                                                     // sets STBY=1 (~10 µA, registers retained)
            Thread.sleep(100);
            adc.wakeup();                                                            // Exit standby, () → None
                                                                                     // clears STBY; blocks until a fresh conversion is available
        }
    }
}
