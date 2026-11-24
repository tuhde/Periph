#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x70);
SHTC3Full sensor(connection);                                   // Create SHTC3 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Soft reset and ID check, () → bool

    // --- Identify the chip ---
    // The ID word carries the SHTC3 product code in bits 11 and 5:0.
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0, id = 0;
    sensor.read_id(id);                                             // Read the ID word, (id&) → bool
    Serial.print("id "); Serial.print((unsigned)(id), HEX); Serial.println();

    // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
    // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
    for (int i = 0; i < 6; i++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        Serial.print(""); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
        delay(10000);
    }

    // --- Compare normal and low-power mode ---
    // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
    sensor.read(t, rh);
    Serial.print("normal    "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.set_low_power(true);                                     // Select low-power mode, (on) → void
    sensor.read(t, rh);
    Serial.print("low power "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.set_low_power(false);

    // --- Dew point and raw words, then finish asleep ---
    Serial.print("dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println(); // Compute dew point, () → float °C
    sensor.read_raw(t_raw, rh_raw);                                 // Read raw codes, (t_raw&, rh_raw&) → bool
    Serial.print("raw T "); Serial.print((unsigned long)(t_raw)); Serial.print(" raw RH "); Serial.print((unsigned long)(rh_raw)); Serial.println();
    sensor.sleep();                                                 // Sleep command, () → void
}

void loop() {
}
