///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.rfid.Mfrc522Full

def transport = new I2CTransport(1, 0x28)
try {
    def mfrc = new Mfrc522Full(transport, Mfrc522Full.BUS_I2C)

    final int CREDITS_BLOCK = 4
    final long INITIAL_CREDITS = 10

    // --- Prepaid-card credit counter ---
    // Simulates a transit-gate / vending-machine credit system using a MIFARE
    // Classic value block. The factory default key A (FF FF FF FF FF FF) is
    // used for the demo only — replace with a per-deployment secret in any
    // real access-control system.

    // --- Detect a card and select it for authenticated access ---
    byte[] uid = mfrc.selectCard()                            // anticollision/Select only, () → byte[] | null
    if (uid == null) {
        println("no card in field")
    } else {
        // --- Authenticate with the well-known MIFARE factory default key A ---
        // In a real deployment this would be a per-card key stored securely
        // (e.g. diversified per card UID and held in an HSM or secure element).
        byte[] factoryKey = [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF] as byte[]
        byte[] uid4 = new byte[4]
        System.arraycopy(uid, 0, uid4, 0, 4)
        if (!mfrc.authenticate(CREDITS_BLOCK, Mfrc522Full.KEY_A, factoryKey, uid4)) {  // MFAuthent, (block, key, uid) → boolean
            println("authentication failed")
        } else {
            // --- Read the current value block; initialise it if unprogrammed ---
            byte[] block = mfrc.readBlock(CREDITS_BLOCK)      // read 16-byte block, (blockAddress) → byte[] (16 B)
            boolean allZero = block != null
            if (allZero) {
                for (byte b : block) if (b != 0) { allZero = false; break }
            }
            if (allZero) {
                byte[] vb = new byte[16]
                vb[0] = (byte) (INITIAL_CREDITS & 0xFF)
                vb[1] = (byte) ((INITIAL_CREDITS >> 8) & 0xFF)
                vb[2] = (byte) ((INITIAL_CREDITS >> 16) & 0xFF)
                vb[3] = (byte) ((INITIAL_CREDITS >> 24) & 0xFF)
                long inv = ~INITIAL_CREDITS
                vb[4] = (byte) (inv & 0xFF)
                vb[5] = (byte) ((inv >> 8) & 0xFF)
                vb[6] = (byte) ((inv >> 16) & 0xFF)
                vb[7] = (byte) ((inv >> 24) & 0xFF)
                vb[8]  = vb[0]; vb[9]  = vb[1]; vb[10] = vb[2]; vb[11] = vb[3]
                vb[12] = (byte) CREDITS_BLOCK
                vb[13] = (byte) (~CREDITS_BLOCK & 0xFF)
                vb[14] = (byte) CREDITS_BLOCK
                vb[15] = (byte) (~CREDITS_BLOCK & 0xFF)
                mfrc.writeBlock(CREDITS_BLOCK, vb)            // write 16 bytes, (block, data=16 B) → boolean
                mfrc.restoreValue(CREDITS_BLOCK)              // restore + Transfer, (block) → boolean
            }

            // --- "Spend" one credit; refuse if balance is zero ---
            block = mfrc.readBlock(CREDITS_BLOCK)             // read current value, (block) → byte[] (16 B)
            if (block != null) {
                long credits = ((block[0] & 0xFF) as long) |
                               (((block[1] & 0xFF) as long) << 8) |
                               (((block[2] & 0xFF) as long) << 16) |
                               (((block[3] & 0xFF) as long) << 24)
                if (credits <= 0) {
                    println("Access denied — no credits remaining")
                } else {
                    mfrc.decrementValue(CREDITS_BLOCK, 1)     // decrement + Transfer, (block, delta) → boolean
                    byte[] updated = mfrc.readBlock(CREDITS_BLOCK)  // read updated value, (block) → byte[] (16 B)
                    if (updated != null) {
                        long newBalance = ((updated[0] & 0xFF) as long) |
                                          (((updated[1] & 0xFF) as long) << 8) |
                                          (((updated[2] & 0xFF) as long) << 16) |
                                          (((updated[3] & 0xFF) as long) << 24)
                        println("spent 1 credit — remaining: ${newBalance}")
                    }
                }
            }
            mfrc.stopCrypto()                                 // clear MFCrypto1On, () → void
        }
        mfrc.haltCard()                                       // send HLTA, () → void
    }
} finally {
    transport.close()
}
