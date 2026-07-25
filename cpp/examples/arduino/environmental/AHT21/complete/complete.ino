#include <Wire.h>
#include "I2CTransport.h"
#include "AHT21.h"

I2CTransport transport(Wire, 0x38);
AHT21Full aht(transport);                                              // Create AHT21 driver, (transport, addr=0x38) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println(aht.is_calibrated());                               // Check calibration status, () → bool
                                                                       // reads CAL bit from status byte
    Serial.println(aht.is_busy());                                     // Check busy status, () → bool
                                                                       // reads BUSY bit from status byte

    float t, h;
    aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
                                                                       // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    Serial.print(t);   Serial.print(" C  ");
    Serial.print(h);   Serial.println(" %RH");

    Serial.println(aht.temperature());                                 // Read temperature only, () → float °C
                                                                       // triggers full measurement, returns temperature_c
    Serial.println(aht.humidity());                                    // Read humidity only, () → float %RH
                                                                       // triggers full measurement, returns humidity_pct

    float tc, hc;
    bool crc_ok = aht.read_with_crc(tc, hc);                           // Read with CRC verification, (temperature_c, humidity_pct) → bool
                                                                       // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    Serial.print(tc);  Serial.print(" C  ");
    Serial.print(hc);  Serial.print(" %RH  CRC: ");
    Serial.println(crc_ok);

    aht.soft_reset();                                                  // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms for recovery
}

void loop() {}
