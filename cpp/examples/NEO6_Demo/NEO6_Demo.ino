// To use I2C (DDC) instead of UART:
//   #include "../../src/transport/I2CTransport.h"
//   I2CTransport transport(Wire, 0x42);
//   NEO6Full gps(transport, NEO6BusType::I2c);
// To use SPI instead of UART:
//   #include "../../src/transport/SPITransport.h"
//   SPITransport transport(SPI, 5, SPISettings(200000, MSBFIRST, SPI_MODE0));
//   NEO6Full gps(transport, NEO6BusType::Spi);

#include <Arduino.h>
#include "../../src/transport/UARTTransport.h"
#include "../../src/chips/gnss/NEO6.h"

// --- Portable GPS logger ---
// The module self-configures at factory defaults (9600 baud NMEA, 1 Hz); no
// CFG messages are needed for a basic position log. Runs for 60 seconds,
// polling update() far faster than the 1 Hz sentence rate so no sentence is
// missed, and prints one line per second once a fresh GGA has been parsed.
UARTTransport transport(Serial1);
NEO6Full gps(transport);                                 // Create NEO-6 driver, (transport, bus_type=Uart)

unsigned long startMs;

void setup() {
    Serial.begin(115200);
    Serial1.begin(9600);
    startMs = millis();
}

void loop() {
    if (millis() - startMs >= 60000) {
        while (true) delay(1000);  // done
    }

    bool gotFix = gps.update();                          // Read + parse one NMEA sentence, () → bool

    // --- No fix yet: show the wait state ---
    // gpsFix alone would not be trustworthy here; update() already only
    // reports true once the GGA fix-status field confirms a real fix, so
    // a plain fix() == 0 check is enough to detect the waiting state.
    if (gps.fix() == 0) {
        Serial.print("waiting for fix... satellites in use: ");
        Serial.println(gps.satellites());
    }
    // --- Fix acquired: log the full position record ---
    // Cold-start TTFF is ~26 s typical outdoors; once gotFix flips true the
    // position, altitude, and HDOP fields below are all populated together.
    else if (gotFix) {
        Serial.print(gps.utcTime());
        Serial.print("  lat=");
        Serial.print(gps.latitude(), 6);
        Serial.print("  lon=");
        Serial.print(gps.longitude(), 6);
        Serial.print("  alt=");
        Serial.print(gps.altitude(), 1);
        Serial.print(" m  sats=");
        Serial.print(gps.satellites());
        Serial.print("  hdop=");
        Serial.println(gps.hdop());
    }

    delay(200);
}
