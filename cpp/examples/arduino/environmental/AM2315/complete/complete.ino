#include <Wire.h>
#include <Periph.h>
#include <math.h>

I2CConnection connection(Wire, 0x5C);
AM2315Full sensor(connection);                                           // Create AM2315 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    Wire.setClock(100000);                                              // standard mode only (<= 100 kHz)
    delay(2000);
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    sensor.read(t, rh);                                                      // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                             // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
    Serial.print(t, 1); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 1); Serial.print(" %RH"); Serial.println();
    // sensor.read_humidity() → float %RH (NAN on failure)
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 1); Serial.print(" C"); Serial.println();
    // sensor.read_temperature() → float °C (NAN on failure)
    sensor.read_raw(rh_raw, t_raw);                                          // Read raw words, (humidity_raw, temperature_raw) → bool
                                                                             // humidity x10; temperature x10 as sign + magnitude
    Serial.print("raw RH "); Serial.print((unsigned)(rh_raw)); Serial.print(" raw T "); Serial.print((unsigned)(t_raw)); Serial.println();
    sensor.read_fresh(t, rh);                                                // Real-time value, (t, rh) → bool
                                                                             // two reads >= 2 s apart; returns the second
    Serial.print("fresh "); Serial.print(t, 1); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.read_registers(0x08, 7, regs);                                    // Read registers, (start, count, out) → bool
                                                                             // function 0x03, 1-10 registers, CRC verified
    Serial.print("register 0x08 = "); Serial.print("0x"); Serial.print((unsigned)(regs[0]), HEX); Serial.println();
    sensor.read_device_info(model, version, device_id);                      // Read device information, (model, version, device_id) → bool
    Serial.print("model "); Serial.print((unsigned)(model)); Serial.print(" version "); Serial.print((unsigned)(version)); Serial.print(" device id "); Serial.print("0x"); Serial.print((unsigned long)(device_id), HEX); Serial.println();
    sensor.write_user_registers(0x1234, 0xABCD);                             // Write user registers, (user1, user2) → bool
                                                                             // registers 0x10-0x13
    sensor.read_user_registers(user1, user2);                                // Read user registers, (user1, user2) → bool
    Serial.print("user registers "); Serial.print("0x"); Serial.print((unsigned)(user1), HEX); Serial.print(" "); Serial.print("0x"); Serial.print((unsigned)(user2), HEX); Serial.println();
    sensor.write_registers(0x10, regs, 2);                                   // Write registers, (start, data, len) → bool
                                                                             // only the user registers 0x10-0x13 are allowed
    sensor.wake();                                                           // Wake the sensor, () → void
                                                                             // dummy write, the NACK is ignored
    Serial.print("last error "); Serial.print((unsigned)(sensor.last_error())); Serial.println();
    // sensor.last_error() → uint8_t, 0x80-0x84 after a sensor error response, else 0
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
