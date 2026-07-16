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

public class Demo {
    public static void main(String[] args) throws Exception {
        // --- Portable GPS logger ---
        // The module self-configures at factory defaults (9600 baud NMEA, 1 Hz);
        // no CFG messages are needed for a basic position log. Runs for 60
        // seconds, polling update() far faster than the 1 Hz sentence rate so
        // no sentence is missed, and prints one line per second once a fresh
        // GGA has been parsed.
        try (var transport = new UARTTransport("/dev/ttyS0")) {        // open UART, 9600 8N1, (port, baudRate=9600, ...) → UARTTransport
            var gps = new Neo6Full(transport);                          // construct driver, (transport, busType=UART) → Neo6Full

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 60_000) {
                boolean gotFix = gps.update();                          // read + parse one NMEA sentence, () → boolean

                // --- No fix yet: show the wait state ---
                // gpsFix alone would not be trustworthy here; update() already
                // only reports true once the GGA fix-status field confirms a
                // real fix, so a plain fix() == 0 check is enough to detect
                // the waiting state.
                if (gps.fix() == 0) {
                    System.out.println("waiting for fix... satellites in use: " + gps.satellites());

                // --- Fix acquired: log the full position record ---
                // Cold-start TTFF is ~26 s typical outdoors; once gotFix flips
                // true the position, altitude, and HDOP fields below are all
                // populated together.
                } else if (gotFix) {
                    System.out.printf("%s  lat=%.6f  lon=%.6f  alt=%.1f m  sats=%d  hdop=%s%n",
                            gps.utcTime(), gps.latitude(), gps.longitude(), gps.altitude(),
                            gps.satellites(), gps.hdop());
                }

                Thread.sleep(200);
            }
        }
    }
}
