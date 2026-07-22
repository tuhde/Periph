///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.rfid.Mfrc522Full

def transport = new I2CTransport(1, 0x28)                     // open I²C bus 1, device 0x28, (bus, address) → I2CTransport
try {
    def mfrc = new Mfrc522Full(transport, Mfrc522Full.BUS_I2C)        // construct driver, (transport, busType=BUS_I2C) → Mfrc522Full

    int[] v = mfrc.version()                                  // read version register, () → int[2]{chipType, version}
                                                                // for MFRC522 v[0]=0x09, v[1]=1 (v1.0) or 2 (v2.0)
    println("MFRC522 chip=0x${Integer.toHexString(v[0])} version=${v[1]}")

    boolean ok = mfrc.selfTest()                              // run digital self test, () → boolean
                                                                // compares 64 FIFO bytes against the version-specific reference
    println("self_test: ${ok ? 'PASS' : 'FAIL'}")

    mfrc.antennaOn()                                          // enable antenna driver (TX1+TX2), () → void
    mfrc.setAntennaGain(38)                                   // set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                // 38 dB gives better read range on most antennas
    println("current gain: ${mfrc.antennaGain()} dB")         // read receiver gain, () → int dB

    mfrc.reset()                                              // soft reset and reinitialise, () → void
                                                                // re-runs the full initialization sequence

    byte[] uid = mfrc.selectCard()                            // anticollision/Select (leaves card active), () → byte[] | null
    if (uid != null) {
        StringBuilder sb = new StringBuilder("UID: ")
        for (byte b : uid) sb.append(String.format("%02X", b & 0xFF))
        println(sb)
        byte[] factoryKey = [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF] as byte[]  // well-known default key — see spec
        byte[] uid4 = new byte[4]
        System.arraycopy(uid, 0, uid4, 0, 4)
        if (mfrc.authenticate(4, Mfrc522Full.KEY_A, factoryKey, uid4)) {  // run MFAuthent, (block, keyType, key=6 B, uid=4 B) → boolean
            byte[] block = mfrc.readBlock(4)                  // read 16-byte block, (blockAddress) → byte[] (16 B)
                                                                // requires successful authenticate for the containing sector
            if (block != null) {
                StringBuilder sb2 = new StringBuilder("block 4: ")
                for (byte b : block) sb2.append(String.format("%02X", b & 0xFF))
                println(sb2)
            }
            mfrc.decrementValue(4, 1)                          // decrement value block, (block, delta=long) → boolean
                                                                // runs Decrement + Transfer to the same block
            mfrc.stopCrypto()                                  // clear MFCrypto1On, () → void
                                                                // required before authenticating a different sector
        }
        mfrc.haltCard()                                        // send HLTA, () → void
    }
} finally {
    transport.close()
}
