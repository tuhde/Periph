#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x77);
MS5611Full sensor(connection);                                  // Create MS5611 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool

    // --- Calibration data ---
    // Six coefficients and a CRC-4 are factory-programmed into the PROM.
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    sensor.read_prom(prom);                                         // Read the PROM words, (words[8]) → bool
    sensor.coefficients(k);                                         // Calibration coefficients, (c[6]) → void
    Serial.print("C1 "); Serial.print((unsigned long)(k[0])); Serial.print(" C2 "); Serial.print((unsigned long)(k[1])); Serial.print(" C3 "); Serial.print((unsigned long)(k[2])); Serial.print(" C4 "); Serial.print((unsigned long)(k[3])); Serial.print(" C5 "); Serial.print((unsigned long)(k[4])); Serial.print(" C6 "); Serial.print((unsigned long)(k[5])); Serial.println();
    Serial.print("crc ok "); Serial.print((unsigned long)(sensor.prom_crc_ok())); Serial.print(" serial "); Serial.print((unsigned long)(sensor.serial_code())); Serial.println(); // Verify the PROM CRC-4, () → bool

    // --- Barometric altimeter / variometer: 20 s, one reading per second ---
    // Altitude relative to the first reading; the climb rate is the change per second.
    float reference = sensor.read_pressure();                       // Read pressure only, () → float Pa
    float previous = 0, lowest = 100;
    for (int i = 0; i < 20; i++) {
        sensor.read(t, p);                                          // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
        if (t < lowest) lowest = t;
        float height = sensor.altitude(reference);                  // Altitude from pressure, (sea_level_pa) → float m
        Serial.print(""); Serial.print(t, 2); Serial.print(" C "); Serial.print(p, 1); Serial.print(" Pa "); Serial.print(height, 2); Serial.print(" m "); Serial.print(i == 0 ? 0.0f : height - previous, 2); Serial.print(" m/s"); Serial.println();
        previous = height;
        delay(1000);
    }

    // --- Noise and time versus oversampling: ten readings each ---
    // Higher ratios take longer per reading and spread less.
    static const uint16_t osrs[5] = {256, 512, 1024, 2048, 4096};
    for (int o = 0; o < 5; o++) {
        sensor.set_oversampling(osrs[o]);                           // Select oversampling ratio, (osr) → bool
        float lo = 1e9f, hi = -1e9f;
        for (int n = 0; n < 10; n++) {
            float v = sensor.read_pressure();                       // Read pressure only, () → float Pa
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        Serial.print("OSR "); Serial.print((unsigned long)(osrs[o])); Serial.print(" spread "); Serial.print(hi - lo, 1); Serial.print(" Pa"); Serial.println();
    }
    sensor.set_oversampling(4096);

    // --- Raw codes with and without the second-order correction ---
    sensor.read_raw(d1, d2);                                        // Read raw ADC codes, (d1&, d2&) → bool
    Serial.print("raw D1 "); Serial.print((unsigned long)(d1)); Serial.print(" D2 "); Serial.print((unsigned long)(d2)); Serial.println();
    sensor.compensate(d1, d2, t, p, false);                         // Compensate raw codes, (d1, d2, temperature_c&, pressure_pa&, second_order) → void
    Serial.print("first order  "); Serial.print(t, 2); Serial.print(" C "); Serial.print(p, 1); Serial.print(" Pa"); Serial.println();
    sensor.compensate(d1, d2, t, p);
    Serial.print("second order "); Serial.print(t, 2); Serial.print(" C "); Serial.print(p, 1); Serial.print(" Pa"); Serial.println();
    Serial.print("lowest temperature seen "); Serial.print(lowest, 2); Serial.print(" C"); Serial.println();

    // --- Non-blocking conversion, then reset ---
    sensor.start_conversion(MS5611Full::PRESSURE);                  // Start a conversion, (kind, osr=0) → bool
    delay(12);
    sensor.read_adc(adc);                                           // Read the ADC result, (value&) → bool
    Serial.print("D1 "); Serial.print((unsigned long)(adc)); Serial.println();
    sensor.reset();                                                 // Reset and reload PROM, () → bool
}

void loop() {
}
