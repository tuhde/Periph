///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.UARTTransport
import it.uhde.periph.chips.gnss.Neo6Full

// To use I2C (DDC) instead of UART:
//   import it.uhde.periph.transport.I2CTransport
//   import it.uhde.periph.chips.gnss.BusType
//   I2CTransport(1, 0x42).use { transport -> val gps = Neo6Full(transport, BusType.I2C) }

fun main() {
    UARTTransport("/dev/ttyS0").use { transport ->               // open UART, 9600 8N1, (port, baudRate=9600, ...) → UARTTransport
        val gps = Neo6Full(transport)                              // construct driver, (transport, busType=UART) → Neo6Full

        gps.setRate(1)                                             // set navigation update rate, (hz) → Unit
                                                                    // writes CFG-RATE with measRate = 1000/hz ms
        gps.setPlatform(0)                                         // set dynamic platform model, (model 0-8) → Unit
                                                                    // writes CFG-NAV5 with mask=dynModel only
        gps.saveConfig()                                           // persist current configuration, () → Unit
                                                                    // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM

        repeat(200) {
            if (gps.update()) {                                    // read + parse one NMEA sentence, () → Boolean
                println("${gps.latitude()} ${gps.longitude()} ${gps.altitude()}")
                                                                    // decimal degrees / decimal degrees / meters MSL
                println("${gps.speed()} ${gps.course()}")
                                                                    // speed over ground, () → Double? m/s
                                                                    // course over ground, () → Double? deg
                println("${gps.utcTime()} ${gps.utcDate()}")
                                                                    // UTC time of last fix sentence, () → String? hhmmss.ss
                                                                    // UTC date of last RMC sentence, () → String? ddmmyy
                println(gps.hdop())                                // horizontal dilution of precision, () → Double?
            }
            Thread.sleep(50)
        }

        val navStatus = gps.pollUbx(0x01, 0x03)                   // poll a UBX message and return its payload, (msgClass, msgId) → ByteArray
        println("NAV-STATUS payload length: ${navStatus.size}")

        gps.coldStart()                                            // force a cold start via CFG-RST, () → Unit
    }
}
