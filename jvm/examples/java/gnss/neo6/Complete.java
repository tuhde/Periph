///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.UARTConnection;
import it.uhde.periph.chips.gnss.Neo6Full;

// To use I2C (DDC) instead of UART:
//   import it.uhde.periph.connection.I2CConnection;
//   try (var connection = new I2CConnection(1, 0x42)) {
//       var gps = new Neo6Full(connection, Neo6Full.BusType.I2C);

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new UARTConnection("/dev/ttyS0")) {       // open UART, 9600 8N1, (port, baudRate=9600, ...) → UARTConnection
            var gps = new Neo6Full(connection);                         // construct driver, (connection, busType=UART) → Neo6Full

            gps.setRate(1);                                            // set navigation update rate, (hz) → void
                                                                        // writes CFG-RATE with measRate = 1000/hz ms
            gps.setPlatform(0);                                        // set dynamic platform model, (model 0-8) → void
                                                                        // writes CFG-NAV5 with mask=dynModel only
            gps.saveConfig();                                          // persist current configuration, () → void
                                                                        // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM

            for (int i = 0; i < 200; i++) {
                if (gps.update()) {                                    // read + parse one NMEA sentence, () → boolean
                    System.out.println(gps.latitude() + " " + gps.longitude() + " " + gps.altitude());
                                                                        // decimal degrees / decimal degrees / meters MSL
                    System.out.println(gps.speed() + " " + gps.course());
                                                                        // speed over ground, () → Double m/s
                                                                        // course over ground, () → Double deg
                    System.out.println(gps.utcTime() + " " + gps.utcDate());
                                                                        // UTC time of last fix sentence, () → String hhmmss.ss
                                                                        // UTC date of last RMC sentence, () → String ddmmyy
                    System.out.println(gps.hdop());                    // horizontal dilution of precision, () → Double
                }
                Thread.sleep(50);
            }

            byte[] navStatus = gps.pollUbx(0x01, 0x03);                // poll a UBX message and return its payload, (msgClass, msgId) → byte[]
            System.out.println("NAV-STATUS payload length: " + navStatus.length);

            gps.coldStart();                                           // force a cold start via CFG-RST, () → void
        }
    }
}
