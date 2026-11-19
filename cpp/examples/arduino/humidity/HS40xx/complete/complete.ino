#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x54);
HS40xxFull sensor(connection);                                      // Create HS40xx driver, (connection) → void

static volatile uint8_t alert_status = 0;
static void on_alert(uint8_t status) { alert_status = status; }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t hb = 0, tb = 0, st = 0, reg = 0;
    uint32_t id = 0;
    sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                    // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
    Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    t = sensor.read_temperature();                                  // Read temperature only, () → float °C
                                                                    // no-hold 0xF3, 3 bytes; NAN on failure
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C"); Serial.println();
    rh = sensor.read_humidity();                                    // Read humidity only, () → float %RH
                                                                    // NAN on failure
    Serial.print("humidity "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.read_hold(t, rh);                                        // Read with clock stretching, (t, rh) → bool °C, %RH
                                                                    // 0xE5; needs a stretching controller and SCL >= 200 kHz
    sensor.read_raw(rh_raw, t_raw);                                 // Read raw values, (humidity_raw, temperature_raw) → bool
                                                                    // 14-bit values, CRC verified
    Serial.print("raw RH "); Serial.print((unsigned long)(rh_raw)); Serial.print(" raw T "); Serial.print((unsigned long)(t_raw)); Serial.println();
    sensor.set_resolution(12, 12);                                  // Set resolution, (humidity_bits, temperature_bits) → bool
                                                                    // 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
    sensor.get_resolution(hb, tb);                                  // Get resolution, (humidity_bits, temperature_bits) → bool
    Serial.print("resolution "); Serial.print((unsigned long)(hb)); Serial.print("/"); Serial.print((unsigned long)(tb)); Serial.print(" bit"); Serial.println();
    sensor.read_register(0x00, reg);                                // Read a register, (reg, value) → bool
    sensor.write_register(0x00, 0x0F);                              // Write a register, (reg, value) → bool
                                                                    // 0x0F = 14 bit for both channels (default)
    sensor.set_alert_thresholds(60.0f, 55.0f, NAN, NAN, 40.0f, 38.0f);// Set alert thresholds, (rh_high, rh_high_reset, rh_low, rh_low_reset, temp_high, temp_high_reset, temp_low, temp_low_reset) → bool
                                                                    // %RH / °C; NAN leaves a threshold unchanged
    sensor.set_alert_polarity(true);                                // Set ALERT polarity, (active_high) → bool
    sensor.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH | HS40xxFull::SOURCE_TEMP_HIGH);// Enable alert sources, (source) → bool
    sensor.start_periodic(1.0f);                                    // Start periodic mode, (period_s) → bool
                                                                    // 0.5, 1 or 2.5 s
    delay(1500);
    sensor.read_periodic(t, rh, st);                                // Fetch the latest periodic result, (t, rh, status) → bool
                                                                    // status is a SOURCE_* bitmask
    Serial.print("periodic "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH status "); Serial.print((unsigned long)(st)); Serial.println();
    st = sensor.poll_interrupt();                                   // Read alert status, () → uint8_t
                                                                    // SOURCE_* bitmask, 0 on failure
    sensor.on_interrupt(on_alert);                                  // Subscribe to the ALERT pin, (callback, intPin = nullptr) → void
                                                                    // needs an InputPin on the connection (or passed in)
    delay(5000);
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    sensor.disable_interrupt(HS40xxFull::SOURCE_RH_HIGH | HS40xxFull::SOURCE_TEMP_HIGH);// Disable alert sources, (source) → bool
    sensor.stop_periodic();                                         // Stop periodic mode, () → bool
    sensor.read_id(id);                                             // Read the 32-bit sensor ID, (id) → bool
    Serial.print("id 0x"); Serial.print((unsigned long)(id), HEX); Serial.println();
    t = sensor.dew_point();                                         // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    Serial.print("dew point "); Serial.print(t, 2); Serial.print(" C"); Serial.println();
    sensor.soft_reset();                                            // Soft reset, () → bool
                                                                    // stops periodic mode, waits 500 ms
}

void loop() {
    delay(1000);
}
