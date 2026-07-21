///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.rfid.Mfrc522Full

fun main() {
    I2CTransport(1, 0x28).use { transport ->
        val mfrc = Mfrc522Full(transport, Mfrc522Full.BUS_I2C)

        val CREDITS_BLOCK = 4
        val INITIAL_CREDITS = 10L

        // --- Prepaid-card credit counter ---
        // Simulates a transit-gate / vending-machine credit system using a MIFARE
        // Classic value block. The factory default key A (FF FF FF FF FF FF) is
        // used for the demo only — replace with a per-deployment secret in any
        // real access-control system.

        // --- Detect a card and select it for authenticated access ---
        val uid = mfrc.selectCard()                            // anticollision/Select only, () → ByteArray?
        if (uid == null) {
            println("no card in field")
        } else {
            // --- Authenticate with the well-known MIFARE factory default key A ---
            // In a real deployment this would be a per-card key stored securely
            // (e.g. diversified per card UID and held in an HSM or secure element).
            val factoryKey = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            val uid4 = uid.copyOf(4)
            if (!mfrc.authenticate(CREDITS_BLOCK, Mfrc522Full.KEY_A, factoryKey, uid4)) {  // MFAuthent, (block, key, uid) → Boolean
                println("authentication failed")
            } else {
                // --- Read the current value block; initialise it if unprogrammed ---
                var block = mfrc.readBlock(CREDITS_BLOCK)      // read 16-byte block, (blockAddress) → ByteArray? (16 B)
                val allZero = block?.all { it == 0.toByte() } ?: false
                if (allZero) {
                    val vb = ByteArray(16)
                    vb[0] = (INITIAL_CREDITS and 0xFF).toByte()
                    vb[1] = ((INITIAL_CREDITS shr 8) and 0xFF).toByte()
                    vb[2] = ((INITIAL_CREDITS shr 16) and 0xFF).toByte()
                    vb[3] = ((INITIAL_CREDITS shr 24) and 0xFF).toByte()
                    val inv = INITIAL_CREDITS.inv()
                    vb[4] = (inv and 0xFF).toByte()
                    vb[5] = ((inv shr 8) and 0xFF).toByte()
                    vb[6] = ((inv shr 16) and 0xFF).toByte()
                    vb[7] = ((inv shr 24) and 0xFF).toByte()
                    vb[8]  = vb[0]; vb[9]  = vb[1]; vb[10] = vb[2]; vb[11] = vb[3]
                    vb[12] = CREDITS_BLOCK.toByte()
                    vb[13] = (CREDITS_BLOCK.inv() and 0xFF).toByte()
                    vb[14] = CREDITS_BLOCK.toByte()
                    vb[15] = (CREDITS_BLOCK.inv() and 0xFF).toByte()
                    mfrc.writeBlock(CREDITS_BLOCK, vb)         // write 16 bytes, (block, data=16 B) → Boolean
                    mfrc.restoreValue(CREDITS_BLOCK)           // restore + Transfer, (block) → Boolean
                }

                // --- "Spend" one credit; refuse if balance is zero ---
                block = mfrc.readBlock(CREDITS_BLOCK)           // read current value, (block) → ByteArray? (16 B)
                if (block != null) {
                    val credits = ((block[0].toLong() and 0xFF) or
                                   ((block[1].toLong() and 0xFF) shl 8) or
                                   ((block[2].toLong() and 0xFF) shl 16) or
                                   ((block[3].toLong() and 0xFF) shl 24))
                    if (credits <= 0L) {
                        println("Access denied — no credits remaining")
                    } else {
                        mfrc.decrementValue(CREDITS_BLOCK, 1)   // decrement + Transfer, (block, delta) → Boolean
                        val updated = mfrc.readBlock(CREDITS_BLOCK)  // read updated value, (block) → ByteArray? (16 B)
                        if (updated != null) {
                            val newBalance = ((updated[0].toLong() and 0xFF) or
                                              ((updated[1].toLong() and 0xFF) shl 8) or
                                              ((updated[2].toLong() and 0xFF) shl 16) or
                                              ((updated[3].toLong() and 0xFF) shl 24))
                            println("spent 1 credit — remaining: $newBalance")
                        }
                    }
                }
                mfrc.stopCrypto()                              // clear MFCrypto1On, () → Unit
            }
            mfrc.haltCard()                                    // send HLTA, () → Unit
        }
    }
}
