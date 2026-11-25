///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.0+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.MB85RCFull

fun main() {
    I2CConnection(1, 0x50, null, null, 2).use { connection ->                    // open I²C bus 1, device 0x50, (bus, address, intPin, enPin, regBytes=2) → I2CConnection
        val fram = MB85RCFull(connection)                                        // construct driver, (connection) → MB85RCFull

        // --- Persistent boot counter ---
        // A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has
        // no write cycle, so the increment is committed when the write ends.
        val c = fram.read(0x0000, 4)                                             // sequential read, (address, length) → ByteArray
        var counter = c.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } + 1
        fram.write(0x0000, ByteArray(4) { (counter shr (24 - 8 * it)).toByte() })   // sequential write, (address, data) → Unit
        println("Boot count: $counter")

        // --- Ring-buffer logger, back-to-back with no delays ---
        // 1,000 records of 16 bytes are written without any pause; an EEPROM
        // would need ACK polling or delays after every page, FRAM does not.
        val base = 0x0100; val record = 16; val count = 1000; val ring = 200
        val t0 = System.nanoTime()
        for (i in 0 until count) {
            val rec = ByteArray(record)
            for (k in 0 until 4) { rec[k] = (i shr (24 - 8 * k)).toByte(); rec[4 + k] = (counter shr (24 - 8 * k)).toByte() }
            fram.write(base + (i % ring) * record, rec)                          // sequential write, (address, data) → Unit
        }
        val dt = (System.nanoTime() - t0) / 1e9
        println("Logged %d bytes in %.3f s = %d B/s".format(count * record, dt, (count * record / dt).toLong()))

        // --- Verify the last five records ---
        // Read the newest records back from the ring and check their index.
        var ok = true
        for (i in count - 5 until count) {
            val rec = fram.read(base + (i % ring) * record, record)              // sequential read, (address, length) → ByteArray
            val idx = rec.take(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            if (idx != i) ok = false
        }
        println("Last 5 records verified: $ok")

        // --- Clear the log area ---
        // fill() zeroes the whole ring in chunked transactions.
        fram.fill(base, ring * record, 0)                                        // fill a range, (address, length, value=0) → Unit
        println("Log cleared; array size: ${fram.size()} bytes")                 // get array size, () → Int

        // --- Device ID (needs the reserved address 0x7C) ---
        // Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F; skip then.
        try {
            I2CConnection(1, 0x7C).use { idConnection ->                         // open I²C bus 1, device 0x7C, (bus, address) → I2CConnection
                val id = fram.readDeviceId(idConnection, 0x50)                   // read Device ID, (idConnection, address=0x50) → IntArray(3)
                println("Device ID: 0x%03X 0x%X 0x%02X".format(id[0], id[1], id[2]))
            }
        } catch (e: Exception) {
            println("Device ID not available")
        }
    }
}
