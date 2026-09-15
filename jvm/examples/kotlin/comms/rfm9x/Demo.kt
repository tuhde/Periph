///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.comms.Rfm95Full

/**
 * RFM95W demo — two-node round-trip link test.
 */
fun main() {
    SPIConnection(0, 0).use { connection ->                                 // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
        val radio = Rfm95Full(connection, 868_000_000L)                      // create RFM95W driver, (connection, frequencyHz=868e6) → Rfm95Full

        // --- Configure for short-range link test ---
        // SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s window;
        // +17 dBm on PA_BOOST gives enough link margin for desk-top loop-back.
        radio.configure(7, 125.0f, 5)                                        // configure LoRa modem, (sf=7, bandwidthKhz=125.0, codingRate=5) → Unit
        radio.setTxPower(17, true)                                            // set TX power, (powerDbm=17, usePaBoost=true) → Unit

        var loss = 0
        val total = 10
        for (n in 0 until total) {
            val tx = byteArrayOf(
                ((n ushr 24) and 0xFF).toByte(),
                ((n ushr 16) and 0xFF).toByte(),
                ((n ushr  8) and 0xFF).toByte(),
                ( n         and 0xFF).toByte()
            )

            val t0 = System.currentTimeMillis()
            radio.send(tx)                                                     // send packet, (data=ByteArray ≤255 B) → Unit
            val rx = radio.receive(1000)                                       // receive single packet, (timeoutMs=1000) → ByteArray?
            val t1 = System.currentTimeMillis()

            val echo = rx != null && rx.size == 4
                    && rx[0] == tx[0] && rx[1] == tx[1]
                    && rx[2] == tx[2] && rx[3] == tx[3]
            if (echo) {
                val rssi = radio.lastPacketRssi()                                // last packet RSSI, () → Float dBm
                val snr  = radio.lastPacketSnr()                                 // last packet SNR, () → Float dB
                println("[%2d] echo rtt=%d ms  rssi=%.1f  snr=%.1f".format(n, t1 - t0, rssi, snr))
            } else {
                loss++
                println("[%2d] no echo".format(n))
            }
            Thread.sleep(200)
        }
        println("done — ${total - loss}/$total successful, $loss lost")
    }
}
