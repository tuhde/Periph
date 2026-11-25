#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Isl29125Full::I2C_ADDRESS);
Isl29125Full sensor(connection);                                // Create ISL29125 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    Serial.print("device id 0x"); Serial.print((unsigned)(sensor.deviceId()), HEX); Serial.println();// Read DEVICE_ID, () → uint8_t
                                                                    // 0x7D for the ISL29125
    Isl29125Counts c = {0, 0, 0};
    sensor.read(c);                                                 // Read the three channels, (counts&) → bool
    Serial.print("R "); Serial.print((unsigned long)(c.red)); Serial.print(" G "); Serial.print((unsigned long)(c.green)); Serial.print(" B "); Serial.print((unsigned long)(c.blue)); Serial.println();
    Serial.print("red "); Serial.print((unsigned long)(sensor.readRed())); Serial.println();// Read the red channel, () → uint16_t
    Serial.print("green "); Serial.print((unsigned long)(sensor.readGreen())); Serial.println();// Read the green channel, () → uint16_t
    Serial.print("blue "); Serial.print((unsigned long)(sensor.readBlue())); Serial.println();// Read the blue channel, () → uint16_t
    sensor.readRgb(c);                                              // Block-read without waiting, (counts&) → void
    Serial.print("ready "); Serial.print((unsigned long)(sensor.dataReady())); Serial.println();// Query CONVENF, () → bool
                                                                    // clears the status flags on the chip
    bool ok = sensor.setMode(Isl29125Full::MODE_GREEN);             // Select the channels, (mode) → bool
                                                                    // MODE_POWER_DOWN, _GREEN, _RED, _BLUE, _STANDBY, _RGB, _GREEN_RED, _GREEN_BLUE
    ok = sensor.setMode(Isl29125Full::MODE_RGB);                    // Back to all three channels, (mode) → bool
    ok = sensor.setRange(375);                                      // Set the full-scale range, (lux) → bool
                                                                    // 375 or 10000
    ok = sensor.setRange(10000);
    ok = sensor.setResolution(12);                                  // Set the ADC resolution, (bits) → bool
                                                                    // 12 or 16
    ok = sensor.setResolution(16);
    sensor.setSync(false);                                          // Select the conversion start, (external) → void
                                                                    // true: rising edge of INT starts the conversion
    ok = sensor.setIrCompensation(true, 0x3F);                      // Set the IR compensation, (offset, adjust) → bool
                                                                    // adjust 0-63; offset selects codes 106-169
    ok = sensor.setThresholds(1000, 50000);                         // Set the threshold window, (low, high) → bool
    uint16_t lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    Serial.print("window "); Serial.print((unsigned long)(lo)); Serial.print(" - "); Serial.print((unsigned long)(hi)); Serial.println();
    ok = sensor.setInterruptChannel(Isl29125Full::CHANNEL_GREEN);   // Choose the monitored channel, (channel) → bool
                                                                    // CHANNEL_NONE, _GREEN, _RED or _BLUE
    ok = sensor.setPersistence(2);                                  // Set the persistence, (count) → bool
                                                                    // 1, 2, 4 or 8 integration cycles
    sensor.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);         // Enable the interrupt, (source) → void
                                                                    // SOURCE_THRESHOLD or SOURCE_CONVERSION
    delay(2000);
    Serial.print("poll 0x"); Serial.print((unsigned)(sensor.pollInterrupt()), HEX); Serial.println();// Read and clear STATUS, () → uint8_t
                                                                    // 0x04 brown-out, 0x02 conversion, 0x01 threshold
    sensor.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);        // Disable the interrupt, (source) → void
    Serial.print("status 0x"); Serial.print((unsigned)(sensor.status()), HEX); Serial.println();// Raw STATUS, () → uint8_t
    sensor.setMode(Isl29125Full::MODE_POWER_DOWN);
    sensor.softReset();                                             // Software reset, () → void
                                                                    // all registers to defaults
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
