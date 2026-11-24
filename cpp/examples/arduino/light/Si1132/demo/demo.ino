#include <math.h>
#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Si1132Full::I2C_ADDRESS);
Si1132Full sensor(connection);                                  // Create Si1132 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    sensor.init();                                                  // Initialize the chip, () → bool

    // --- UV and light monitor ---
    // Prints the calibration bytes, reads UV index, visible and infrared counts
    // once per second, shows the effect of gain 0/2/4 and of the high-range bits,
    // reads the temperature and VDD auxiliary channels, runs autonomous mode at
    // 0.5 s while polling the interrupt status, then restricts the channels to UV
    // only and finishes with pause and reset.
    uint8_t cal[12];
    sensor.read_calibration(cal);                                   // Read the factory calibration, (out[12]) → bool
    Serial.print("calibration "); Serial.print((unsigned)(cal[0]), HEX); Serial.print(" "); Serial.print((unsigned)(cal[1]), HEX); Serial.print(" "); Serial.print((unsigned)(cal[2]), HEX); Serial.print(" "); Serial.print((unsigned)(cal[3]), HEX); Serial.println();
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    for (int i = 0; i < 20; i++) {
        sensor.read(uv, vis, ir);                                   // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
        Serial.print("UV "); Serial.print(uv, 2); Serial.print(" visible "); Serial.print((unsigned long)(vis)); Serial.print(" infrared "); Serial.print((unsigned long)(ir)); Serial.println();
        delay(1000);
    }
    const uint8_t gains[3] = {0, 2, 4};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.set_gain(gains[i], gains[i]);                        // Set the ADC gain, (visible 0-7, infrared 0-7) → bool
        sensor.read(uv, vis, ir);
        Serial.print("gain "); Serial.print((unsigned long)(gains[i])); Serial.print(" visible "); Serial.print((unsigned long)(vis)); Serial.print(" infrared "); Serial.print((unsigned long)(ir)); Serial.println();
    }
    sensor.set_gain(0, 0);
    sensor.set_high_range(true, true);                              // Set the high signal range, (visible, infrared) → bool
    sensor.read(uv, vis, ir);
    Serial.print("high range visible "); Serial.print((unsigned long)(vis)); Serial.print(" infrared "); Serial.print((unsigned long)(ir)); Serial.println();
    sensor.set_high_range(false, false);
    sensor.read_temperature_raw(aux);                               // Read die temperature, (raw&) → bool
    Serial.print("temperature raw "); Serial.print((unsigned long)(aux)); Serial.println();
    sensor.read_vdd_raw(aux);                                       // Read supply voltage, (raw&) → bool
    Serial.print("vdd raw "); Serial.print((unsigned long)(aux)); Serial.println();
    sensor.start_autonomous(0.5f);                                  // Start autonomous mode, (interval_s 0-2.05) → bool
    sensor.enable_interrupt();                                      // Enable the INT pin, () → bool
    for (int i = 0; i < 10; i++) {
        delay(500);
        uint8_t st = sensor.poll_interrupt();                       // Poll interrupt status, () → uint8_t
        if (st) {
            sensor.read_raw(vis, ir, aux);                          // Read the data registers, (visible&, infrared&, aux&) → bool
            Serial.print("INT 0x"); Serial.print((unsigned)(st), HEX); Serial.print(" visible "); Serial.print((unsigned long)(vis)); Serial.println();
        }
    }
    sensor.disable_interrupt();                                     // Disable the INT pin, () → bool
    sensor.pause();                                                 // Pause autonomous mode, () → bool
    sensor.set_channels(true, false, false);                        // Select channels, (uv, visible, infrared, aux=false) → bool
    Serial.print("UV only "); Serial.print(sensor.read_uv_index(), 2); Serial.println();// Read the UV index, () → float
    sensor.reset();                                                 // Reset the chip, () → bool
}

void loop() {
}
