#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x38);
AHT10Full aht(connection);                                              // Create AHT10 driver, (connection, addr=0x38) → void

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

    uint32_t raw_rh, raw_t;
    aht.read_raw(raw_rh, raw_t);                                       // Read raw 20-bit values, (raw_humidity, raw_temperature) → bool
                                                                       // same measurement as read(), undecoded
    Serial.print(raw_rh);  Serial.print(" ");  Serial.println(raw_t);

    Serial.println(aht.read_status(), HEX);                            // Read status byte, () → uint8_t
                                                                       // bit 7 BUSY, bits 6:5 MODE, bit 3 CAL
    Serial.println(aht.dew_point());                                   // Dew point, () → float °C
                                                                       // one fresh measurement, Magnus formula

    aht.soft_reset();                                                  // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms, re-runs the init command
}

void loop() {}
