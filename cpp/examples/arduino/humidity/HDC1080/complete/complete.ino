#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
HDC1080Full sensor(connection);                                         // Create HDC1080 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;

    uint64_t serial = 0; uint16_t cfg = 0;
    sensor.init();                                                      // Write default configuration, () → void
                                                                        // 0x1000: 14-bit T and RH, sequence mode, heater off
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    Serial.print("serial id 0x"); Serial.print((unsigned)((serial) >> 32), HEX); Serial.print((unsigned)((serial) & 0xFFFFFFFF), HEX); Serial.println();
    cfg = sensor.read_config();                                         // Read the configuration register, () → uint16_t
    Serial.print("config 0x"); Serial.print((unsigned)(cfg), HEX); Serial.println();
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // sequence mode, polls the NACKed read header (timeout 50 ms)
    Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 2); Serial.print(" C"); Serial.println();
    // sensor.read_temperature() → float °C (NAN on failure), single mode
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 1); Serial.print(" %RH"); Serial.println();
    // sensor.read_humidity() → float %RH (NAN on failure), single mode
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit words, bits 1:0 zero
    Serial.print("raw T "); Serial.print((unsigned)(raw_t)); Serial.print(" raw RH "); Serial.print((unsigned)(raw_rh)); Serial.println();

    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
                                                                        // T 14/11 bit, RH 14/11/8 bit; false for other values
    Serial.print("conversion time "); Serial.print(sensor.conversion_time() * 1000.0f, 2); Serial.print(" ms"); Serial.println();
    // sensor.conversion_time() → float s, typical T + RH time
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // runs only during measurements; readings drift while on
    Serial.print("heater on "); Serial.print((unsigned)(sensor.is_heater_on())); Serial.println();
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);                                           // Heater off
    Serial.print("battery low "); Serial.print((unsigned)(sensor.battery_low())); Serial.println();
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // sets RST, waits 15 ms, rewrites the stored configuration

    Serial.print("manufacturer 0x"); Serial.println(sensor.manufacturer_id(), HEX);   // Manufacturer ID (0x5449), () → uint16_t
    Serial.print("device 0x"); Serial.println(sensor.device_id(), HEX);               // Device ID (0x1050), () → uint16_t
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
