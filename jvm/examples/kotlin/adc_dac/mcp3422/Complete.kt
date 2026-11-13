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
        println(adc.readRaw(0))                                                                     // one-shot code, (channel 0–1) → int
        println(adc.readVoltage(1))                                                                 // one-shot voltage, (channel 0–1) → double V
        println(adc.readAll())                                                                      // both channels, () → double[] V
        println(adc.readScan(intArrayOf(0, 1, 0)))                                                  // scan a list of channels, (channels) → double[] V

        adc.setResolution(18)                                                                       // select 18 bit / 3.75 SPS, (bits 12|14|16|18) → void; IllegalArgumentException otherwise
        adc.setGain(8)                                                                              // select PGA x8, (gain 1|2|4|8) → void
        println("${adc.getResolution()} ${adc.getGain()}")                                          // read back the selection, () → int, int
        println("${adc.lsb()} ${adc.fullScale()}")                                                  // LSB and full scale, () → double V, double V

        adc.setResolution(12)                                                                       // back to 12 bit / 240 SPS, (bits) → void
        adc.setGain(1)                                                                              // back to x1, (gain) → void
        adc.setChannel(0)                                                                           // channel for continuous conversion, (channel 0–1) → void
        adc.startContinuous()                                                                       // start continuous conversion, () or (channel) → void; the first sample after a change is discarded
        println(adc.isContinuous())                                                                 // report the mode, () → boolean
        println(adc.readContinuousRaw())                                                            // next fresh code, () → int
        println(adc.readContinuous())                                                               // next fresh voltage, () → double V
        println("${adc.readConfig()} ${adc.isReady()}")                                             // config byte and RDY, () → int, boolean
        adc.stopContinuous()                                                                        // back to one-shot / standby, () → void

        adc.generalCallConvert()                                                                    // start a conversion on every device, () → void; sends 0x00 0x08
        Thread.sleep(100)
        println(adc.readPendingRaw())                                                               // result of that conversion, () → int
        adc.generalCallReset()                                                                      // reset to power-on defaults, () → void; sends 0x00 0x06, the driver state follows
    }
}
