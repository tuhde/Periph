///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.other.Mpr121Full

private val NOTES = arrayOf(
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4",
)

fun main() {
    I2CConnection(1, 0x5A).use { connection ->                         // Open I2C connection, (bus=1, address=0x5A) → I2CConnection
        val mpr = Mpr121Full(connection)                                 // Create MPR121 driver, (connection) → Mpr121Full
                                                                       // defaults, all 12 electrodes enabled
        var previous = 0
        repeat(1000) {
            val mask = mpr.touched()                                      // Read 12-bit touch bitmask, () → Int bitmask
                                                                       // bit n=1 means ELEn is currently touched
            val newlyPressed = mask and previous.inv()
            val newlyReleased = mask.inv() and previous
            for (n in 0 until 12) {
                if (newlyPressed and (1 shl n) != 0) {
                    println("NOTE ON:  ${NOTES[n]}")
                }
                if (newlyReleased and (1 shl n) != 0) {
                    println("NOTE OFF: ${NOTES[n]}")
                }
            }
            previous = mask
            Thread.sleep(50)
        }
    }
}
