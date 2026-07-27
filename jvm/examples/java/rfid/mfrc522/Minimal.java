///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.Mfrc522Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x28)) {       // open I²C bus 1, device 0x28, (bus, address) → I2CConnection
            var mfrc = new Mfrc522Minimal(connection, Mfrc522Minimal.BUS_I2C);  // construct driver, (connection, busType=BUS_I2C) → Mfrc522Minimal
                                                                                // runs the initialization sequence

            while (true) {
                boolean present = mfrc.isCardPresent();         // detect card in field, () → boolean
                byte[] uid = mfrc.readUid();                    // read card UID (REQA → anticollision → HLTA), () → byte[] | null
                StringBuilder sb = new StringBuilder("present=").append(present).append(" uid=");
                if (uid != null) for (byte b : uid) sb.append(String.format("%02X", b & 0xFF));
                System.out.println(sb);
                Thread.sleep(500);
            }
        }
    }
}
