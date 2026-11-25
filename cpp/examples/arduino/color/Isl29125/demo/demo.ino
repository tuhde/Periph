#include <math.h>
#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Isl29125Full::I2C_ADDRESS);
Isl29125Full sensor(connection);                                // Create ISL29125 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);

    // --- Colour sampler ---
    // Prints the device ID, samples R/G/B as a normalised colour, compares the
    // 375 lux and 10 000 lux ranges and the 12/16-bit resolutions, varies the IR
    // compensation, reads green only, arms a threshold interrupt on green with
    // persistence 2 (polled through pollInterrupt), then powers down and resets.
    Serial.print("device id 0x"); Serial.print((unsigned)(sensor.deviceId()), HEX); Serial.println();// Read DEVICE_ID, () → uint8_t
    Isl29125Counts c = {0, 0, 0};
    for (int i = 0; i < 20; i++) {
        sensor.read(c);                                             // Read the three channels, (counts&) → bool
        uint16_t top = c.red > c.green ? c.red : c.green;
        if (c.blue > top) top = c.blue;
        if (top == 0) top = 1;
        Serial.print("#"); Serial.print((unsigned)((unsigned long)c.red * 255 / top), HEX); Serial.print((unsigned)((unsigned long)c.green * 255 / top), HEX); Serial.print((unsigned)((unsigned long)c.blue * 255 / top), HEX); Serial.println();
        delay(500);
    }
    const uint16_t ranges[2] = {375, 10000};
    for (uint8_t i = 0; i < 2; i++) {
        sensor.setRange(ranges[i]);                                 // Set the range, (lux) → bool
        delay(400);
        sensor.readRgb(c);                                          // Read without waiting, (counts&) → void
        Serial.print(""); Serial.print((unsigned long)(ranges[i])); Serial.print(" lux range G "); Serial.print((unsigned long)(c.green)); Serial.print(" R "); Serial.print((unsigned long)(c.red)); Serial.print(" B "); Serial.print((unsigned long)(c.blue)); Serial.println();
    }
    const uint8_t bits[2] = {12, 16};
    for (uint8_t i = 0; i < 2; i++) {
        sensor.setResolution(bits[i]);                              // Set the resolution, (bits) → bool
        delay(400);
        sensor.readRgb(c);
        Serial.print(""); Serial.print((unsigned long)(bits[i])); Serial.print(" bit full scale "); Serial.print((unsigned long)((1UL << bits[i]) - 1)); Serial.print(" G "); Serial.print((unsigned long)(c.green)); Serial.println();
    }
    sensor.setRange(10000);
    const uint8_t adjust[3] = {0, 0x20, 0x3F};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.setIrCompensation(false, adjust[i]);                 // Set the IR compensation, (offset, adjust) → bool
        delay(400);
        sensor.readRgb(c);
        Serial.print("IR compensation "); Serial.print((unsigned long)(adjust[i])); Serial.print(" G "); Serial.print((unsigned long)(c.green)); Serial.print(" R "); Serial.print((unsigned long)(c.red)); Serial.print(" B "); Serial.print((unsigned long)(c.blue)); Serial.println();
    }
    sensor.setIrCompensation(true, 0x3F);
    sensor.setMode(Isl29125Full::MODE_GREEN);                       // Green only, (mode) → bool
    delay(300);
    Serial.print("green only "); Serial.print((unsigned long)(sensor.readGreen())); Serial.println();// Read green, () → uint16_t
    sensor.setMode(Isl29125Full::MODE_RGB);
    Serial.print("ready "); Serial.print((unsigned long)(sensor.dataReady())); Serial.println();// Query CONVENF, () → bool
    uint16_t base = sensor.readGreen();
    uint16_t lo = base - base / 4;
    uint16_t hi = base + base / 4 > 0xFFFF ? 0xFFFF : base + base / 4;
    sensor.setThresholds(lo, hi);                                   // Set the window, (low, high) → bool
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    Serial.print("window "); Serial.print((unsigned long)(lo)); Serial.print(" - "); Serial.print((unsigned long)(hi)); Serial.println();
    sensor.setInterruptChannel(Isl29125Full::CHANNEL_GREEN);        // Choose the channel, (channel) → bool
    sensor.setPersistence(2);                                       // Set the persistence, (count) → bool
    sensor.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);         // Enable the interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        delay(500);
        uint8_t st = sensor.pollInterrupt();                        // Read and clear STATUS, () → uint8_t
        if (st & Isl29125Full::STATUS_THRESHOLD) {
            Serial.print("window crossed, green "); Serial.print((unsigned long)(sensor.readGreen())); Serial.println();
        }
    }
    sensor.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);        // Disable the interrupt, (source) → void
    Serial.print("status 0x"); Serial.print((unsigned)(sensor.status()), HEX); Serial.println();
    sensor.setMode(Isl29125Full::MODE_POWER_DOWN);
    sensor.softReset();                                             // Software reset, () → void
}

void loop() {
}
