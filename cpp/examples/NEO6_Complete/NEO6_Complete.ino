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

UARTTransport transport(Serial1);
NEO6Full gps(transport);                                 // Create NEO-6 driver, (transport, bus_type=Uart)

void setup() {
    Serial.begin(115200);
    Serial1.begin(9600);

    gps.setRate(1);                                      // Set navigation update rate, (hz) → void
                                                          // writes CFG-RATE with measRate = 1000/hz ms
    gps.setPlatform(0);                                   // Set dynamic platform model, (model 0-8) → void
                                                          // writes CFG-NAV5 with mask=dynModel only
    gps.saveConfig();                                     // Persist current configuration, () → void
                                                          // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM
}

void loop() {
    if (gps.update()) {                                  // Read + parse one NMEA sentence, () → bool
        Serial.print(gps.latitude(), 6);
        Serial.print(", ");
        Serial.print(gps.longitude(), 6);
        Serial.print(", ");
        Serial.println(gps.altitude(), 1);
                                                          // decimal degrees, decimal degrees, meters MSL
        Serial.print(gps.speed());                       // Speed over ground, () → m/s
        Serial.print(", ");
        Serial.println(gps.course());                    // Course over ground, () → deg
        Serial.print(gps.utcTime());                      // UTC time of last fix sentence, () → hhmmss.ss
        Serial.print(", ");
        Serial.println(gps.utcDate());                    // UTC date of last RMC sentence, () → ddmmyy
        Serial.println(gps.hdop());                       // Horizontal dilution of precision, () → float

        uint8_t payload[256];
        size_t payloadLen = 0;
        if (gps.pollUbx(0x01, 0x03, payload, payloadLen, sizeof(payload))) {  // Poll a UBX message, (msg_class, msg_id, out_payload, out_len, max_len) → bool
            Serial.print("NAV-STATUS payload bytes: ");
            Serial.println(payloadLen);
        }

        gps.coldStart();                                  // Force a cold start via CFG-RST, () → void
    }
    delay(50);
}
