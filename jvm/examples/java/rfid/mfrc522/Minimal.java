///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.rfid.Mfrc522Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x28)) {       // open I²C bus 1, device 0x28, (bus, address) → I2CTransport
            var mfrc = new Mfrc522Minimal(transport, Mfrc522Minimal.BUS_I2C);  // construct driver, (transport, busType=BUS_I2C) → Mfrc522Minimal
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
