#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
HTU31DFull sensor(connection);                                  // Create HTU31D driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Soft reset, () → void

    // --- Condensation-aware hygrometer ---
    // Prints the serial number and the diagnostic byte, reads temperature and
    // humidity with the dew point 15 times, compares the four oversampling
    // settings with their typical conversion time, shows the heater effect for
    // 10 s, demonstrates the non-blocking conversion pair and soft-resets.
    uint32_t serial = 0;
    sensor.serial_number(serial);                                   // Read the serial number, (serial&) → bool
    Serial.print("serial 0x"); Serial.print((unsigned)(serial), HEX); Serial.print(" diagnostic 0x"); Serial.print((unsigned)(sensor.diagnostic()), HEX); Serial.println();
    float t = 0, rh = 0;
    for (int i = 0; i < 15; i++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        Serial.print(""); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH dew "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();
        delay(2000);
    }
    for (uint8_t osr = 0; osr < 4; osr++) {
        sensor.set_resolution(osr, osr);                            // Set oversampling, (osr_rh, osr_t) → bool
        sensor.read(t, rh);
        Serial.print("osr "); Serial.print((unsigned long)(osr)); Serial.print(" typical "); Serial.print(sensor.conversion_time() * 1000.0f, 2); Serial.print(" ms: "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    }
    sensor.set_resolution(3, 3);
    sensor.read(t, rh);
    Serial.print("before heater "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.set_heater(true);                                        // Switch the heater on, (on) → void
    Serial.print("heater on "); Serial.print((unsigned long)(sensor.is_heater_on())); Serial.print(" diagnostic 0x"); Serial.print((unsigned)(sensor.diagnostic()), HEX); Serial.println();
    delay(10000);
    sensor.set_heater(false);                                       // Heater off
    sensor.read(t, rh);
    Serial.print("after heater "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.start_conversion();                                      // Start a conversion, () → void
    delay(30);
    sensor.read_result(t, rh);                                      // Read last conversion result, (temperature_c&, humidity_pct&) → bool
    Serial.print("result "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.software_reset();                                        // Soft reset, () → void
}

void loop() {
}
