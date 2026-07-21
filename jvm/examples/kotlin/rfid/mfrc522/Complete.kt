///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.rfid.Mfrc522Full

fun main() {
    I2CTransport(1, 0x28).use { transport ->                  // open I²C bus 1, device 0x28, (bus, address) → I2CTransport
        val mfrc = Mfrc522Full(transport, Mfrc522Full.BUS_I2C)        // construct driver, (transport, busType=BUS_I2C) → Mfrc522Full

        val v = mfrc.version()                                 // read version register, () → IntArray (size 2)
                                                                // for MFRC522 v[0]=0x09, v[1]=1 (v1.0) or 2 (v2.0)
        println("MFRC522 chip=0x%X version=%d".format(v[0], v[1]))

        val ok = mfrc.selfTest()                               // run digital self test, () → Boolean
                                                                // compares 64 FIFO bytes against the version-specific reference
        println("self_test: ${if (ok) "PASS" else "FAIL"}")

        mfrc.antennaOn()                                       // enable antenna driver (TX1+TX2), () → Unit
        mfrc.setAntennaGain(38)                                // set receiver gain, (dB=18/23/33/38/43/48) → Unit
                                                                // 38 dB gives better read range on most antennas
        println("current gain: ${mfrc.antennaGain()} dB")      // read receiver gain, () → Int dB

        mfrc.reset()                                           // soft reset and reinitialise, () → Unit
                                                                // re-runs the full initialization sequence

        val uid = mfrc.selectCard()                            // anticollision/Select (leaves card active), () → ByteArray?
        if (uid != null) {
            val sb = StringBuilder("UID: ")
            uid.forEach { sb.append("%02X".format(it.toInt() and 0xFF)) }
            println(sb)
            val factoryKey = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())  // well-known default key — see spec
            val uid4 = uid.copyOf(4)
            if (mfrc.authenticate(4, Mfrc522Full.KEY_A, factoryKey, uid4)) {  // run MFAuthent, (block, keyType, key=6 B, uid=4 B) → Boolean
                val block = mfrc.readBlock(4)                  // read 16-byte block, (blockAddress) → ByteArray? (16 B)
                                                                // requires successful authenticate for the containing sector
                if (block != null) {
                    val sb2 = StringBuilder("block 4: ")
                    block.forEach { sb2.append("%02X".format(it.toInt() and 0xFF)) }
                    println(sb2)
                }
                mfrc.decrementValue(4, 1)                      // decrement value block, (block, delta=Long) → Boolean
                                                                // runs Decrement + Transfer to the same block
                mfrc.stopCrypto()                              // clear MFCrypto1On, () → Unit
                                                                // required before authenticating a different sector
            }
            mfrc.haltCard()                                    // send HLTA, () → Unit
        }
    }
}
