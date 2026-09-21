///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Ad7706Full;
import it.uhde.periph.chips.adc_dac.Ad7706Minimal;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0, 3, 5_000_000)) {    // open SPI bus 0, device 0, Mode 3, 5 MHz
            var adc = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_2_4576MHZ);   // construct and initialise the AD7706, (connection, vref=2.5 V, mclkHz=2_457_600 Hz) → Ad7706Full
                                                                                      // constructor runs the Initialization Sequence (gain 1, bipolar, 50 Hz, self-calibrate Channel 1)

            adc.configure(2, Ad7706Full.GAIN_8, true, true, 60);                    // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                                      // sets gain/bipolar/buffered/output_rate for the given channel; does not calibrate
            adc.selfCalibrate(2);                                                    // Self-calibrate channel, (channel=2) → None
                                                                                      // runs internal self-calibration, blocking until DRDY indicates completion
            adc.configure(3, Ad7706Full.GAIN_8, true, true, 60);                    // Configure channel 3, (channel=3, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
            adc.selfCalibrate(3);                                                    // Self-calibrate channel, (channel=3) → None

            int off2 = adc.getOffsetCalibration(2);                                 // Read offset calibration, (channel=2) → int 24-bit
            int gain2 = adc.getGainCalibration(2);                                  // Read gain calibration, (channel=2) → int 24-bit
            System.out.printf("ch2 offset=%d gain=%d%n", off2, gain2);

            int raw1 = adc.readRaw(1);                                              // Read raw 16-bit code, (channel=1) → int 16-bit
                                                                                      // blocks until DRDY, returns raw Data Register code
            float v1 = adc.readVoltage(1);                                           // Read voltage, (channel=1) → float V
                                                                                      // converts raw code to volts using channel's current gain/bipolar setting
            float v2 = adc.readVoltage(2);                                           // Read voltage, (channel=2) → float V
            float v3 = adc.readVoltage(3);                                           // Read voltage, (channel=3) → float V
            System.out.printf("ch1 raw=%d ch1 v=%.4f ch2 v=%.4f ch3 v=%.4f%n", raw1, v1, v2, v3);

            adc.standby();                                                          // Enter standby, () → None
                                                                                      // sets STBY=1 (~10 µA, registers retained)
            Thread.sleep(100);
            adc.wakeup();                                                           // Exit standby, () → None
                                                                                      // clears STBY; blocks until a fresh conversion is available

            adc.reset();                                                             // Hardware reset, () → None
                                                                                      // pulses RESET low for >=100 ns; all registers return to power-on defaults
        }
    }
}
