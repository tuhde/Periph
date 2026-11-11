///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS7828Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS7828Full.DEFAULT_ADDRESS).use { connection ->              // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS7828Full(connection)                                                           // construct driver, (connection, vref=null → internal 2.5 V) → ADS7828Full
        adc.init()                                                                                  // Enable internal reference, () → Unit
        val raw = adc.readRaw(3)                                                                    // Read CH3 raw code, (channel 0–7) → int
        // single-ended, interleaved channel encoding; returns 0–4095
        val v3 = adc.readVoltage(3)                                                                 // Read CH3 voltage, (channel 0–7) → double V
        // raw / 4096 × vref
        val all = adc.readAll()                                                                     // Read all eight channels, () → double[8] V
        // one conversion per channel, CH0…CH7
        val draw = adc.readDifferentialRaw(0, false)                                                // Read pair 0 raw code, (pair 0–3, inverted=false) → int
        // CH0 (+) / CH1 (−); a negative difference reads 0
        val dv = adc.readDifferential(1, true)                                                      // Read pair 1 inverted, (pair 0–3, inverted=false) → double V
        // CH3 (+) / CH2 (−)
        val sv = adc.readSignedDifferential(0)                                                      // Read pair 0 signed, (pair 0–3) → double V
        // two conversions (normal and inverted), range −vref … +vref
        val lsb = adc.lsb()                                                                         // Get code step, () → double V
        // vref / 4096 (610 µV at 2.5 V)
        val scan = adc.readScan(intArrayOf(0, 2, 4, 6))                                             // Scan channels, (channels int[]) → int[]
        // one raw conversion per channel in the given order
        adc.setReference(5.0)                                                                       // Use external reference, (vref Double V; null = internal) → Unit
        // PD1 = 0 from now on; the reference must already be stable
        val ref = adc.getReference()                                                                // Get reference voltage, () → double V
        val isInternal = adc.usesInternalReference()                                                  // Internal reference selected?, () → boolean
        adc.setReference(null)                                                                      // Use internal reference, (vref Double V; null = internal) → Unit
        // re-runs the reference turn-on and 1 ms settle wait
        adc.setPowerMode(ADS7828Full.REF_ON_ADC_ON)                                                 // Set PD bits, (pd 0–3) → Unit
        // internal reference requires PD1 = 1 (values 2 or 3)
        adc.powerDown()                                                                             // Power down completely, () → Unit
        // lone command with PD=00; next read re-enables the reference
        println("$raw $v3 $draw $dv $sv $lsb $ref $isInternal ${scan[0]} ${all[7]}")
    }
}
