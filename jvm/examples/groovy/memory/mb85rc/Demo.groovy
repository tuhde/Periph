///usr/bin/env jbang "$0" "$@" ; exit $?
//GROOVY 4
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.MB85RCFull

def connection = new I2CConnection(1, 0x50, null, null, 2)                        // open I²C bus 1, device 0x50, (bus, address, intPin, enPin, regBytes=2) → I2CConnection
try {
    def fram = new MB85RCFull(connection)                                         // construct driver, (connection) → MB85RCFull

    // --- Persistent boot counter ---
    // A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has
    // no write cycle, so the increment is committed when the write ends.
    byte[] c = fram.read(0x0000, 4)                                               // sequential read, (address, length) → byte[]
    long counter = c.inject(0L) { long acc, byte b -> (acc << 8) | (b & 0xFF) } + 1
    fram.write(0x0000, (0..<4).collect { (byte) (counter >> (24 - 8 * it)) } as byte[])   // sequential write, (address, data) → void
    println("Boot count: ${counter}")

    // --- Ring-buffer logger, back-to-back with no delays ---
    // 1,000 records of 16 bytes are written without any pause; an EEPROM
    // would need ACK polling or delays after every page, FRAM does not.
    int base = 0x0100, record = 16, count = 1000, ring = 200
    long t0 = System.nanoTime()
    for (int i = 0; i < count; i++) {
        byte[] rec = new byte[record]
        for (int k = 0; k < 4; k++) {
            rec[k] = (byte) (i >> (24 - 8 * k))
            rec[4 + k] = (byte) (counter >> (24 - 8 * k))
        }
        fram.write(base + (i % ring) * record, rec)                               // sequential write, (address, data) → void
    }
    double dt = (System.nanoTime() - t0) / 1e9
    println(String.format('Logged %d bytes in %.3f s = %d B/s', count * record, dt, (long) (count * record / dt)))

    // --- Verify the last five records ---
    // Read the newest records back from the ring and check their index.
    boolean ok = true
    for (int i = count - 5; i < count; i++) {
        byte[] rec = fram.read(base + (i % ring) * record, record)                // sequential read, (address, length) → byte[]
        int idx = ((rec[0] & 0xFF) << 24) | ((rec[1] & 0xFF) << 16) | ((rec[2] & 0xFF) << 8) | (rec[3] & 0xFF)
        if (idx != i) ok = false
    }
    println("Last 5 records verified: ${ok}")

    // --- Clear the log area ---
    // fill() zeroes the whole ring in chunked transactions.
    fram.fill(base, ring * record, 0)                                             // fill a range, (address, length, value=0) → void
    println("Log cleared; array size: ${fram.size()} bytes")                      // get array size, () → int

    // --- Device ID (needs the reserved address 0x7C) ---
    // Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F; skip then.
    try {
        def idConnection = new I2CConnection(1, 0x7C)                             // open I²C bus 1, device 0x7C, (bus, address) → I2CConnection
        try {
            def id = fram.readDeviceId(idConnection, 0x50)                        // read Device ID, (idConnection, address=0x50) → int[3]
            println(String.format('Device ID: 0x%03X 0x%X 0x%02X', id[0], id[1], id[2]))
        } finally {
            idConnection.close()
        }
    } catch (Exception e) {
        println('Device ID not available')
    }
} finally {
    connection.close()
}
