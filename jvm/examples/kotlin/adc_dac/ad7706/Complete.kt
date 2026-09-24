///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.adc_dac.Ad7706Full
import it.uhde.periph.chips.adc_dac.Ad7706Minimal

fun main() {
    SPIConnection(0, 0, 3, 5_000_000).use { connection ->                // open SPI bus 0, device 0, Mode 3, 5 MHz
        val adc = Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_2_4576MHZ) // construct and initialise the AD7706, (connection, vref=2.5 V, mclkHz=2_457_600 Hz) → Ad7706Full
                                                                              // constructor runs the Initialization Sequence (gain 1, bipolar, 50 Hz, self-calibrate Channel 1)

        adc.configure(2, Ad7706Full.GAIN_8, true, true, 60)               // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                              // sets gain/bipolar/buffered/output_rate for the given channel; does not calibrate
        adc.selfCalibrate(2)                                              // Self-calibrate channel, (channel=2) → None
                                                                              // runs internal self-calibration, blocking until DRDY indicates completion
        adc.configure(3, Ad7706Full.GAIN_8, true, true, 60)               // Configure channel 3, (channel=3, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
        adc.selfCalibrate(3)                                              // Self-calibrate channel, (channel=3) → None

        val off2 = adc.getOffsetCalibration(2)                            // Read offset calibration, (channel=2) → int 24-bit
        val gain2 = adc.getGainCalibration(2)                             // Read gain calibration, (channel=2) → int 24-bit
        println("ch2 offset=$off2 gain=$gain2")

        val raw1 = adc.readRaw(1)                                          // Read raw 16-bit code, (channel=1) → int 16-bit
                                                                              // blocks until DRDY, returns raw Data Register code
        val v1 = adc.readVoltage(1)                                        // Read voltage, (channel=1) → float V
                                                                              // converts raw code to volts using channel's current gain/bipolar setting
        val v2 = adc.readVoltage(2)                                        // Read voltage, (channel=2) → float V
        val v3 = adc.readVoltage(3)                                        // Read voltage, (channel=3) → float V
        println("ch1 raw=$raw1 ch1 v=${"%.4f".format(v1)} ch2 v=${"%.4f".format(v2)} ch3 v=${"%.4f".format(v3)}")

        adc.standby()                                                     // Enter standby, () → None
                                                                              // sets STBY=1 (~10 µA, registers retained)
        Thread.sleep(100)
        adc.wakeup()                                                      // Exit standby, () → None
                                                                              // clears STBY; blocks until a fresh conversion is available

        adc.reset()                                                        // Hardware reset, () → None
                                                                              // pulses RESET low for >=100 ns; all registers return to power-on defaults
    }
}
