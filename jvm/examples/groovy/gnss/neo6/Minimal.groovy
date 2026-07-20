///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.UARTTransport
import it.uhde.periph.chips.gnss.Neo6Minimal

// To use I2C (DDC) instead of UART:
//   import it.uhde.periph.transport.I2CTransport
//   import it.uhde.periph.chips.gnss.BusType
//   def transport = new I2CTransport(1, 0x42)
//   def gps = new Neo6Minimal(transport, BusType.I2C)

def transport = new UARTTransport('/dev/ttyS0')          // open UART, 9600 8N1, (port, baudRate=9600, ...) → UARTTransport
try {
    def gps = new Neo6Minimal(transport)                  // construct driver, (transport, busType=UART) → Neo6Minimal

    while (true) {
        if (gps.update()) {                               // read + parse one NMEA sentence, () → boolean
            println("${gps.latitude()} ${gps.longitude()} ${gps.altitude()}")
        }
        Thread.sleep(50)
    }
} finally {
    transport.close()
}
