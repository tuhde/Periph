///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1113Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, ADS1113Full.DEFAULT_ADDRESS)                    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
try {
        def adc = new ADS1113Full(connection)                                               // construct driver, (connection) → ADS1113Full

        // --- Configure for low-noise slow sampling ---
        // 8 SPS gives the longest integration time per conversion, which
        // suppresses mains and switching noise on a slowly varying voltage.
        adc.setDataRate(ADS1113Full.DR_8)                                                   // Set data rate, (dr 0–7) → void

        // --- Log single-shot readings ---
        // Each readVoltage() wakes the chip, converts and powers down again.
        5.times {
            def v = adc.readVoltage()                                                       // Read voltage, () → double V
            printf("%.5f V%n", v)
            Thread.sleep(1000)
        }

        // --- Average continuous readings ---
        // Back-to-back conversions averaged over 1.25 s reduce random noise.
        adc.startContinuous()                                                               // Start continuous, () → void
        Thread.sleep(200)
        double acc = 0
        10.times {
            acc += adc.readContinuous()                                                     // Read continuous, () → double V
            Thread.sleep(125)
        }
        adc.stopContinuous()                                                                // Stop continuous, () → void
        printf("average %.5f V%n", acc / 10)
} finally {
    connection.close()
}
