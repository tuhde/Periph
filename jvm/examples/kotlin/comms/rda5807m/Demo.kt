///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.comms.Rda5807mFull

// FM band scanner: starts at the bottom of the world-wide band and repeatedly
// seeks upward (SKMODE=1, the Minimal/Full default, stops seeking at the band
// limit) until the top of the band is reached, printing frequency, signal
// strength, stereo/mono status, and — when available — the RDS Program
// Service (station) name for every station found along the way.
fun main() {
    I2CTransport(1, 0x10).use { transport ->
        val fm = Rda5807mFull(transport, 87.5, 10)

        // --- FM band scanner ---
        // Start at the bottom of the world-wide band and repeatedly seek
        // upward with SKMODE=1 (stop at band limit) so a seek that returns
        // null means the top of the band has been reached.
        fm.enableRds(true)

        println("Scanning...")
        var count = 0
        while (true) {
            val freq = fm.seek(true) ?: break
            if (!fm.isStation()) continue

            val rssi = fm.signalStrength()
            val stereo = fm.isStereo()

            // --- Try to read the Program Service (station) name via RDS ---
            // Group types 0A/0B carry the 8-character PS name, four segments
            // of two characters each, addressed by block B bits 1:0. Give
            // the decoder up to 2 seconds to assemble a full name.
            val psChars = arrayOfNulls<Char>(8)
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                if (fm.rdsReady()) {
                    val group = fm.readRdsGroup()
                    if (group != null) {
                        val blockB = group[1]
                        val blockD = group[3]
                        val groupType = blockB shr 12
                        val isBVariant = (blockB shr 11) and 1
                        if (groupType == 0 && isBVariant == 0) {
                            val segment = blockB and 0x03
                            psChars[segment * 2] = (blockD shr 8).toChar()
                            psChars[segment * 2 + 1] = (blockD and 0xFF).toChar()
                            if (psChars.all { it != null }) break
                        }
                    }
                }
                Thread.sleep(40)
            }

            val label = if (psChars.all { it != null }) psChars.joinToString("").trim() else "(no RDS name)"
            println("%.2f MHz  RSSI=%d  %s  %s".format(freq, rssi, if (stereo) "stereo" else "mono", label))
            count++
        }

        println()
        println("Scan complete: $count station(s) found")
    }
}
