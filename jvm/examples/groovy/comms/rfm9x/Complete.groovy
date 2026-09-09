///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.comms.Rfm95Full

def connection = new SPIConnection(0, 0)                                     // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
try {
    def radio = new Rfm95Full(connection, 868_000_000L)                        // create RFM95W full driver, (connection, frequencyHz=868e6) → Rfm95Full

    int ver = radio.version()                                                  // read silicon version, () → int
    println "version: 0x${Integer.toHexString(ver)}"                           // expect 0x12 (SX1276)

    radio.configure(7, 125.0f, 5)                                              // configure LoRa modem, (sf=6–12, bandwidthKhz=7.8–500, codingRate=5–8, crc=true) → void
                                                                                // sets SF=7, BW=125 kHz, CR 4/5
    radio.setTxPower(17, true)                                                 // set TX power, (powerDbm=2–20, usePaBoost=true) → void
                                                                                // PA_BOOST pin, +17 dBm
    radio.setFrequency(868_000_000L)                                            // change carrier frequency, (frequencyHz=862e6–1020e6) → void
    radio.standby()                                                            // enter STDBY mode, () → void

    radio.send('hello world'.bytes)                                             // send packet, (data=byte[] ≤255 B) → void
                                                                                // STDBY → fill FIFO → TX → poll TxDone → STDBY

    byte[] pkt = radio.receive(2000)                                            // receive single packet, (timeoutMs=2000) → byte[] | null
                                                                                // RXSINGLE → poll RxDone/RxTimeout → read FIFO
    if (pkt != null) {
        float rssi = radio.lastPacketRssi()                                     // last packet RSSI, () → float dBm
        float snr  = radio.lastPacketSnr()                                      // last packet SNR, () → float dB
        printf "rx %d B  rssi=%.1f  snr=%.1f%n", pkt.length, rssi, snr
    }

    radio.receiveContinuous()                                                   // enter continuous RX, () → void
    byte[] buf = radio.readPacket()                                             // read buffered packet, () → byte[] | null
    radio.stopReceive()                                                        // return to STDBY from RX_CONT, () → void

    radio.sleep()                                                               // enter SLEEP mode, () → void
    Thread.sleep(250)
    radio.standby()
} finally {
    connection.close()
}
