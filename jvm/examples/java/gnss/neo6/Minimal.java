///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.UARTConnection;
import it.uhde.periph.chips.gnss.Neo6Minimal;

// To use I2C (DDC) instead of UART:
//   import it.uhde.periph.connection.I2CConnection;
//   try (var connection = new I2CConnection(1, 0x42)) {
//       var gps = new Neo6Minimal(connection, Neo6Minimal.BusType.I2C);

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new UARTConnection("/dev/ttyS0")) {      // open UART, 9600 8N1, (port, baudRate=9600, ...) → UARTConnection
            var gps = new Neo6Minimal(connection);                     // construct driver, (connection, busType=UART) → Neo6Minimal

            while (true) {
                if (gps.update()) {                                   // read + parse one NMEA sentence, () → boolean
                    System.out.println(gps.latitude() + " " + gps.longitude() + " " + gps.altitude());
                }
                Thread.sleep(50);
            }
        }
    }
}
