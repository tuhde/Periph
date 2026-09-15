///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.comms.Rfm95Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0)) {                    // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
            var radio = new Rfm95Minimal(connection, 868_000_000L);           // create RFM95W driver, (connection, frequencyHz=868e6) → Rfm95Minimal

            radio.send("hello".getBytes());                                   // send packet, (data=byte[] ≤255 B) → void
            System.out.println("sent");
        }
    }
}
