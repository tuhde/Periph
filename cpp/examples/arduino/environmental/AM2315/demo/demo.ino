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
    float t = 0, rh = 0, t_min = 1000.0f, t_max = -1000.0f, h_min = 1000.0f, h_max = -1000.0f;
    uint16_t model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0;
    uint32_t device_id = 0;

    sensor.read_device_info(model, version, device_id);                      // Read device information, (model, version, device_id) → bool
    Serial.print("model "); Serial.print((unsigned)(model)); Serial.print(" version "); Serial.print((unsigned)(version)); Serial.print(" device id "); Serial.print("0x"); Serial.print((unsigned long)(device_id), HEX); Serial.println();

    // --- Weather-station logger: a sample every 5 s, min/max tracking ---
    // The sensor measures after a communication and returns that result on the next one,
    // so every sample is the previous measurement; the driver enforces the >= 2 s spacing
    // and the CRC-16 check.
    for (uint8_t i = 1; i <= 12; i++) {
        if (i % 10 == 0) sensor.read_fresh(t, rh);                         // Real-time value, (t, rh) → bool, two reads
        else sensor.read(t, rh);                                           // Read temperature and humidity, (t, rh) → bool
        if (t < t_min) t_min = t;
        if (t > t_max) t_max = t;
        if (rh < h_min) h_min = rh;
        if (rh > h_max) h_max = rh;
        float g = logf((rh > 0.1f ? rh : 0.1f) / 100.0f) + 17.62f * t / (243.12f + t);   // Magnus formula
        float dew = 243.12f * g / (17.62f - g);
        Serial.print("#"); Serial.print((unsigned)(i)); Serial.print("  "); Serial.print(t, 1); Serial.print(" C  "); Serial.print(rh, 1); Serial.print(" %RH  dew point "); Serial.print(dew, 1); Serial.print(" C  (T "); Serial.print(t_min, 1); Serial.print(".."); Serial.print(t_max, 1); Serial.print(", RH "); Serial.print(h_min, 1); Serial.print(".."); Serial.print(h_max, 1); Serial.print(")"); Serial.println();
        delay(5000);
    }

    // --- User registers as a persistent counter ---
    sensor.write_user_registers(12, 0xBEEF);                                 // Write user registers, (user1, user2) → bool
    sensor.read_user_registers(user1, user2);                                // Read user registers, (user1, user2) → bool
    Serial.print("user registers "); Serial.print((unsigned)(user1)); Serial.print(" "); Serial.print("0x"); Serial.print((unsigned)(user2), HEX); Serial.println();
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
