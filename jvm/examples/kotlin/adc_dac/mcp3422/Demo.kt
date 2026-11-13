///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.MCP3422Full
import it.uhde.periph.chips.adc_dac.MCP3422Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, MCP3422Minimal.DEFAULT_ADDRESS).use { connection ->                          // open I²C bus, device address, (bus, address=0x68) → I2CConnection
        val adc = MCP3422Full(connection)                                                           // construct driver, (connection) → MCP3422Full
        // --- CH1: thermistor divider at 16 bit / x1 ---
        for (i in 0 until 3) {
            val raw = adc.readRaw(0)                                                                // one-shot code, (channel 0–1) → int
            println("CH1 raw ${raw} = ${adc.readVoltage(0)} V")                                     // one-shot voltage, (channel 0–1) → double V
            Thread.sleep(1000)
        }

        // --- CH2: bridge sensor at 18 bit / x8 ---
        adc.setResolution(18)                                                                       // select 18 bit / 3.75 SPS, (bits) → void
        adc.setGain(8)                                                                              // select PGA x8, (gain) → void
        val volts = adc.readScan(intArrayOf(1))[0]                                                  // scan channels, (channels) → double[] V
        println("CH2 ${volts * 1e6} uV, LSB ${adc.lsb() / adc.getGain() * 1e6} uV")                 // LSB, () → double V

        // --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
        adc.setResolution(12)
        adc.setGain(1)
        adc.startContinuous(0)                                                                      // start continuous conversion, (channel) → void
        val samples = DoubleArray(20)
        val t0 = System.nanoTime()
        for (i in samples.indices) {
            samples[i] = adc.readContinuous()                                                       // next fresh voltage, () → double V
        }
        val elapsed = (System.nanoTime() - t0) / 1e9
        adc.stopContinuous()                                                                        // back to one-shot / standby, () → void
        val min = samples.min(); val max = samples.max(); val mean = samples.average()
        println("min $min max $max mean $mean V, ${samples.size / elapsed} samples/s")

        adc.generalCallReset()                                                                      // reset to power-on defaults, () → void
    }
}
