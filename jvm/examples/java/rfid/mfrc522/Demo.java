///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.rfid.Mfrc522Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x28)) {       // open I²C bus 1, device 0x28, (bus, address) → I2CTransport
            var mfrc = new Mfrc522Full(transport, Mfrc522Full.BUS_I2C);

            final int CREDITS_BLOCK = 4;
            final long INITIAL_CREDITS = 10;

            // --- Prepaid-card credit counter ---
            // Simulates a transit-gate / vending-machine credit system using a MIFARE
            // Classic value block. The factory default key A (FF FF FF FF FF FF) is
            // used for the demo only — replace with a per-deployment secret in any
            // real access-control system.

            // --- Detect a card and select it for authenticated access ---
            byte[] uid = mfrc.selectCard();                     // anticollision/Select only, () → byte[] | null
            if (uid == null) {
                System.out.println("no card in field");
            } else {
                // --- Authenticate with the well-known MIFARE factory default key A ---
                // In a real deployment this would be a per-card key stored securely
                // (e.g. diversified per card UID and held in an HSM or secure element).
                byte[] factoryKey = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
                byte[] uid4 = new byte[4];
                System.arraycopy(uid, 0, uid4, 0, 4);
                if (!mfrc.authenticate(CREDITS_BLOCK, Mfrc522Full.KEY_A, factoryKey, uid4)) { // MFAuthent, (block, key, uid) → boolean
                    System.out.println("authentication failed");
                } else {
                    // --- Read the current value block; initialise it if unprogrammed ---
                    byte[] block = mfrc.readBlock(CREDITS_BLOCK);  // read 16-byte block, (blockAddress) → byte[] (16 B)
                    boolean allZero = true;
                    if (block != null) {
                        for (byte b : block) if (b != 0) { allZero = false; break; }
                    }
                    if (allZero) {
                        byte[] valueBlock = new byte[16];
                        valueBlock[0] = (byte) (INITIAL_CREDITS & 0xFF);
                        valueBlock[1] = (byte) ((INITIAL_CREDITS >> 8) & 0xFF);
                        valueBlock[2] = (byte) ((INITIAL_CREDITS >> 16) & 0xFF);
                        valueBlock[3] = (byte) ((INITIAL_CREDITS >> 24) & 0xFF);
                        long inv = ~INITIAL_CREDITS;
                        valueBlock[4] = (byte) (inv & 0xFF);
                        valueBlock[5] = (byte) ((inv >> 8) & 0xFF);
                        valueBlock[6] = (byte) ((inv >> 16) & 0xFF);
                        valueBlock[7] = (byte) ((inv >> 24) & 0xFF);
                        valueBlock[8]  = valueBlock[0];
                        valueBlock[9]  = valueBlock[1];
                        valueBlock[10] = valueBlock[2];
                        valueBlock[11] = valueBlock[3];
                        valueBlock[12] = (byte) CREDITS_BLOCK;
                        valueBlock[13] = (byte) (~CREDITS_BLOCK & 0xFF);
                        valueBlock[14] = (byte) CREDITS_BLOCK;
                        valueBlock[15] = (byte) (~CREDITS_BLOCK & 0xFF);
                        mfrc.writeBlock(CREDITS_BLOCK, valueBlock);  // write 16 bytes, (block, data=16 B) → boolean
                        mfrc.restoreValue(CREDITS_BLOCK);            // restore + Transfer, (block) → boolean
                    }

                    // --- "Spend" one credit; refuse if balance is zero ---
                    block = mfrc.readBlock(CREDITS_BLOCK);            // read current value, (block) → byte[] (16 B)
                    if (block != null) {
                        long credits = ((long)(block[0] & 0xFF)) |
                                       ((long)(block[1] & 0xFF) << 8) |
                                       ((long)(block[2] & 0xFF) << 16) |
                                       ((long)(block[3] & 0xFF) << 24);
                        if (credits <= 0) {
                            System.out.println("Access denied — no credits remaining");
                        } else {
                            mfrc.decrementValue(CREDITS_BLOCK, 1);    // decrement + Transfer, (block, delta) → boolean
                            byte[] updated = mfrc.readBlock(CREDITS_BLOCK);  // read updated value, (block) → byte[] (16 B)
                            if (updated != null) {
                                long newBalance = ((long)(updated[0] & 0xFF)) |
                                                  ((long)(updated[1] & 0xFF) << 8) |
                                                  ((long)(updated[2] & 0xFF) << 16) |
                                                  ((long)(updated[3] & 0xFF) << 24);
                                System.out.println("spent 1 credit — remaining: " + newBalance);
                            }
                        }
                    }
                    mfrc.stopCrypto();                            // clear MFCrypto1On, () → void
                }
                mfrc.haltCard();                                  // send HLTA, () → void
            }
        }
    }
}
