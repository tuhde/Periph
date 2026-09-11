///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.comms.Rfm95Full

/**
 * RFM95W demo — two-node round-trip link test.
 */
def connection = new SPIConnection(0, 0)                                     // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
try {
    def radio = new Rfm95Full(connection, 868_000_000L)                        // create RFM95W driver, (connection, frequencyHz=868e6) → Rfm95Full

    // --- Configure for short-range link test ---
    // SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s window;
    // +17 dBm on PA_BOOST gives enough link margin for desk-top loop-back.
    radio.configure(7, 125.0f, 5, true)                                        // configure LoRa modem, (sf=7, bandwidthKhz=125.0, codingRate=5, crc=true) → void
    radio.setTxPower(17, true)                                                 // set TX power, (powerDbm=17, usePaBoost=true) → void

    int loss = 0
    int total = 10
    for (int n = 0; n < total; n++) {
        byte[] tx = [
            (byte)((n >>> 24) & 0xFF),
            (byte)((n >>> 16) & 0xFF),
            (byte)((n >>>  8) & 0xFF),
            (byte)( n         & 0xFF)
        ] as byte[]

        long t0 = System.currentTimeMillis()
        radio.send(tx)                                                          // send packet, (data=byte[] ≤255 B) → void
        byte[] rx = radio.receive(1000)                                        // receive single packet, (timeoutMs=1000) → byte[] | null
        long t1 = System.currentTimeMillis()

        boolean echo = rx != null && rx.length == 4
                && rx[0] == tx[0] && rx[1] == tx[1]
                && rx[2] == tx[2] && rx[3] == tx[3]
        if (echo) {
            float rssi = radio.lastPacketRssi()                                   // last packet RSSI, () → float dBm
            float snr  = radio.lastPacketSnr()                                    // last packet SNR, () → float dB
            printf "[%2d] echo rtt=%d ms  rssi=%.1f  snr=%.1f%n", n, t1 - t0, rssi, snr
        } else {
            loss++
            printf "[%2d] no echo%n", n
        }
        Thread.sleep(200)
    }
    printf "done — %d/%d successful, %d lost%n", total - loss, total, loss
} finally {
    connection.close()
}
