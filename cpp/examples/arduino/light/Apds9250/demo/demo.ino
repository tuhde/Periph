#include <math.h>
#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Apds9250Full::I2C_ADDRESS);
Apds9250Full sensor(connection);                                // Create APDS-9250 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);

    // --- Colour sampler ---
    // Prints the part ID and settings, samples R/G/B/IR as a normalised colour,
    // compares ALS/IR mode, steps gain and resolution with normalised counts,
    // arms a variation and a threshold interrupt on the green channel (polled
    // through pollInterrupt), then disables the sensor and soft-resets it.
    uint8_t part = 0, rev = 0;
    sensor.partId(part, rev);                                       // Read PART_ID, (part&, revision&) → void
    Serial.print("part "); Serial.print((unsigned)(part), HEX); Serial.print(" rev "); Serial.print((unsigned)(rev), HEX); Serial.print(" resolution "); Serial.print((unsigned long)(sensor.getResolution())); Serial.print(" bit rate "); Serial.print((unsigned long)(sensor.getMeasurementRate())); Serial.print(" ms gain "); Serial.print((unsigned long)(sensor.getGain())); Serial.println();
    Apds9250Counts c = {0, 0, 0, 0};
    for (int i = 0; i < 20; i++) {
        sensor.read(c);                                             // Read the four channels, (counts&) → bool
        uint32_t top = c.red > c.green ? c.red : c.green;
        if (c.blue > top) top = c.blue;
        if (top == 0) top = 1;
        Serial.print("#"); Serial.print((unsigned)((unsigned long)c.red * 255 / top), HEX); Serial.print((unsigned)((unsigned long)c.green * 255 / top), HEX); Serial.print((unsigned)((unsigned long)c.blue * 255 / top), HEX); Serial.print(" IR "); Serial.print((unsigned long)(c.ir)); Serial.println();
        delay(500);
    }
    sensor.setMode(false);                                          // Select ALS/IR mode, (colour) → void
    Serial.print("ALS/IR green "); Serial.print((unsigned long)(sensor.readGreen())); Serial.print(" ir "); Serial.print((unsigned long)(sensor.readIr())); Serial.println();
    sensor.setMode(true);                                           // Back to colour mode, (colour) → void
    const uint8_t gains[5] = {1, 3, 6, 9, 18};
    for (uint8_t i = 0; i < 5; i++) {
        sensor.setGain(gains[i]);                                   // Set the gain, (gain) → bool
        delay(300);
        uint32_t g = sensor.readGreen();                            // Read green, () → uint32_t
        Serial.print("gain "); Serial.print((unsigned long)(gains[i])); Serial.print(" green "); Serial.print((unsigned long)(g)); Serial.print(" normalized "); Serial.print(sensor.normalized(g), 0); Serial.println();
    }
    sensor.setGain(3);
    const uint8_t bits[6] = {13, 16, 17, 18, 19, 20};
    for (uint8_t i = 0; i < 6; i++) {
        sensor.setResolution(bits[i]);                              // Set the resolution, (bits) → bool
        delay(500);
        uint32_t g = sensor.readGreen();
        Serial.print(""); Serial.print((unsigned long)(bits[i])); Serial.print(" bit full scale "); Serial.print((unsigned long)((1UL << bits[i]) - 1)); Serial.print(" green "); Serial.print((unsigned long)(g)); Serial.print(" normalized "); Serial.print(sensor.normalized(g), 0); Serial.println();
    }
    sensor.setResolution(18);
    sensor.setMeasurementRate(100);                                 // Set the rate, (milliseconds) → bool
    sensor.setInterruptChannel(Apds9250Full::CHANNEL_GREEN);        // Choose the channel, (channel) → bool
    sensor.setPersistence(2);                                       // Set the persistence, (count) → bool
    sensor.setVariance(64);                                         // Set the variation threshold, (counts) → bool
    sensor.enableInterrupt(Apds9250Full::SOURCE_VARIATION);         // Enable the variation interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        delay(500);
        uint8_t st = sensor.pollInterrupt();                        // Read and clear MAIN_STATUS, () → uint8_t
        if (st & Apds9250Full::STATUS_INTERRUPT) {
            Serial.print("variation interrupt, green "); Serial.print((unsigned long)(sensor.readGreen())); Serial.println();
        }
    }
    uint32_t base = sensor.readGreen();
    sensor.setThresholds(base - base / 4, base + base / 4);         // Set the window, (low, high) → bool
    sensor.enableInterrupt(Apds9250Full::SOURCE_THRESHOLD);         // Enable the threshold interrupt, (source) → void
    delay(5000);
    Serial.print("status 0x"); Serial.print((unsigned)(sensor.pollInterrupt()), HEX); Serial.println();
    sensor.disableInterrupt();                                      // Disable the interrupt, () → void
    sensor.disable();                                               // Standby, () → void
    sensor.softReset();                                             // Software reset, () → void
}

void loop() {
}
