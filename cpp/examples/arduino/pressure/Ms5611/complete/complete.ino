#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x77);
MS5611Full sensor(connection);                                  // Create MS5611 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    bool ok = sensor.read(t, p);                                    // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
                                                                    // OSR 4096, second-order compensation applied
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C pressure "); Serial.print(p, 1); Serial.print(" Pa"); Serial.println();
    Serial.print("pressure "); Serial.print(sensor.read_pressure(), 1); Serial.print(" Pa"); Serial.println(); // Read pressure only, () → float Pa
                                                                    // NAN on failure
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 2); Serial.print(" C"); Serial.println(); // Read temperature only, () → float °C
                                                                    // converts D2 only
    ok = sensor.read_raw(d1, d2);                                   // Read raw ADC codes, (d1&, d2&) → bool
                                                                    // 24-bit pressure and temperature codes
    Serial.print("raw D1 "); Serial.print((unsigned long)(d1)); Serial.print(" raw D2 "); Serial.print((unsigned long)(d2)); Serial.println();
    sensor.compensate(d1, d2, t, p);                                // Compensate raw codes, (d1, d2, temperature_c&, pressure_pa&, second_order=true) → void
                                                                    // pure computation, no bus access
    sensor.compensate(d1, d2, t, p, false);
    sensor.set_oversampling(1024);                                  // Select oversampling ratio, (osr) → bool
                                                                    // 256, 512, 1024, 2048 or 4096
    Serial.print("osr "); Serial.print((unsigned long)(sensor.get_oversampling())); Serial.println(); // Current oversampling ratio, () → uint16
    Serial.print("conversion time "); Serial.print(sensor.conversion_time() * 1000.0f, 3); Serial.print(" ms"); Serial.println(); // Max conversion time, (osr=0) → float s
    sensor.start_conversion(MS5611Full::TEMPERATURE);               // Start a conversion, (kind, osr=0) → bool
                                                                    // PRESSURE = D1, TEMPERATURE = D2; non-blocking
    delay(12);
    ok = sensor.read_adc(adc);                                      // Read the ADC result, (value&) → bool
                                                                    // only after the conversion time, 24-bit
    Serial.print("adc "); Serial.print((unsigned long)(adc)); Serial.println();
    sensor.set_oversampling(4096);
    ok = sensor.read_prom(prom);                                    // Read the PROM words, (words[8]) → bool
    sensor.coefficients(k);                                         // Calibration coefficients, (c[6]) → void
                                                                    // C1 ... C6
    Serial.print("crc ok "); Serial.print((unsigned long)(sensor.prom_crc_ok())); Serial.println(); // Verify the PROM CRC-4, () → bool
    Serial.print("serial "); Serial.print((unsigned long)(sensor.serial_code())); Serial.println(); // Serial code, () → uint16
                                                                    // PROM word 7 bits 15:4
    Serial.print("altitude "); Serial.print(sensor.altitude(), 2); Serial.print(" m"); Serial.println(); // Altitude from pressure, (sea_level_pa=101325) → float m
                                                                    // international barometric formula
    sensor.reset();                                                 // Reset and reload PROM, () → bool
                                                                    // 0x1E, 3 ms wait
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
