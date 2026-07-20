///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.UARTTransport;
import it.uhde.periph.chips.gnss.Neo6Full;

// To use I2C (DDC) instead of UART:
//   import it.uhde.periph.transport.I2CTransport;
//   try (var transport = new I2CTransport(1, 0x42)) {
//       var gps = new Neo6Full(transport, Neo6Full.BusType.I2C);

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var transport = new UARTTransport("/dev/ttyS0")) {       // open UART, 9600 8N1, (port, baudRate=9600, ...) → UARTTransport
            var gps = new Neo6Full(transport);                         // construct driver, (transport, busType=UART) → Neo6Full

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
