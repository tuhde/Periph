///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.comms.Rfm95Full

fun main() {
    SPIConnection(0, 0).use { connection ->                                 // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
        val radio = Rfm95Full(connection, 868_000_000L)                      // create RFM95W full driver, (connection, frequencyHz=868e6) → Rfm95Full

        val ver = radio.version()                                             // read silicon version, () → Int
        println("version: 0x%02X".format(ver))                                // expect 0x12 (SX1276)

        radio.configure(7, 125.0f, 5)                                        // configure LoRa modem, (sf=6–12, bandwidthKhz=7.8–500, codingRate=5–8, crc=true) → Unit
                                                                              // sets SF=7, BW=125 kHz, CR 4/5
        radio.setTxPower(17, true)                                            // set TX power, (powerDbm=2–20, usePaBoost=true) → Unit
                                                                              // PA_BOOST pin, +17 dBm
        radio.setFrequency(868_000_000L)                                      // change carrier frequency, (frequencyHz=862e6–1020e6) → Unit
        radio.standby()                                                       // enter STDBY mode, () → Unit

        radio.send("hello world".toByteArray())                                // send packet, (data=ByteArray ≤255 B) → Unit

        val pkt = radio.receive(2000)                                         // receive single packet, (timeoutMs=2000) → ByteArray?
                                                                              // null on timeout
        if (pkt != null) {
            val rssi = radio.lastPacketRssi()                                  // last packet RSSI, () → Float dBm
            val snr  = radio.lastPacketSnr()                                   // last packet SNR, () → Float dB
            println("rx ${pkt.size} B  rssi=%.1f  snr=%.1f".format(rssi, snr))
        }

        radio.receiveContinuous()                                             // enter continuous RX, () → Unit
        val buf = radio.readPacket()                                          // read buffered packet, () → ByteArray?
        radio.stopReceive()                                                   // return to STDBY from RX_CONT, () → Unit

        radio.sleep()                                                         // enter SLEEP mode, () → Unit
        Thread.sleep(250)
        radio.standby()
    }
}
