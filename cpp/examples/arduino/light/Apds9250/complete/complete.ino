#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Apds9250Full::I2C_ADDRESS);
Apds9250Full sensor(connection);                                // Create APDS-9250 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    uint8_t part = 0, rev = 0;
    sensor.partId(part, rev);                                       // Read PART_ID, (part&, revision&) → void
                                                                    // 0xB, 0x5 for the APDS-9250
    Serial.print("part "); Serial.print((unsigned)(part), HEX); Serial.print(" rev "); Serial.print((unsigned)(rev), HEX); Serial.println();
    Apds9250Counts c = {0, 0, 0, 0};
    sensor.read(c);                                                 // Read the four channels, (counts&) → bool
    Serial.print("R "); Serial.print((unsigned long)(c.red)); Serial.print(" G "); Serial.print((unsigned long)(c.green)); Serial.print(" B "); Serial.print((unsigned long)(c.blue)); Serial.print(" IR "); Serial.print((unsigned long)(c.ir)); Serial.println();
    Serial.print("red "); Serial.print((unsigned long)(sensor.readRed())); Serial.println();// Read the red channel, () → uint32_t
    Serial.print("green "); Serial.print((unsigned long)(sensor.readGreen())); Serial.println();// Read the green / ALS channel, () → uint32_t
    Serial.print("blue "); Serial.print((unsigned long)(sensor.readBlue())); Serial.println();// Read the blue channel, () → uint32_t
    Serial.print("ir "); Serial.print((unsigned long)(sensor.readIr())); Serial.println();// Read the IR channel, () → uint32_t
    Serial.print("ready "); Serial.print((unsigned long)(sensor.dataReady())); Serial.println();// Query LS_DATA, () → bool
                                                                    // clears the status flags on the chip
    sensor.readChannels(c);                                         // Block-read without waiting, (counts&) → void
    sensor.setMode(false);                                          // Select ALS/IR mode, (colour) → void
                                                                    // false: only green and IR are active
    sensor.setMode(true);                                           // Back to colour mode, (colour) → void
    sensor.disable();                                               // Standby, () → void
    sensor.enable();                                                // Activate the sensor, () → void
    bool ok = sensor.setResolution(16);                             // Set the ADC resolution, (bits) → bool
                                                                    // 13, 16, 17, 18, 19 or 20; restarts the measurement
    ok = sensor.setMeasurementRate(200);                            // Set the measurement rate, (milliseconds) → bool
                                                                    // 25, 50, 100, 200, 500, 1000 or 2000
    ok = sensor.setGain(6);                                         // Set the gain, (gain) → bool
                                                                    // 1, 3, 6, 9 or 18; restarts the measurement
    Serial.print("resolution "); Serial.print((unsigned long)(sensor.getResolution())); Serial.print(" bit"); Serial.println();// Read the resolution, () → uint8_t
    Serial.print("rate "); Serial.print((unsigned long)(sensor.getMeasurementRate())); Serial.print(" ms"); Serial.println();// Read the rate, () → uint16_t
    Serial.print("gain "); Serial.print((unsigned long)(sensor.getGain())); Serial.println();// Read the gain, () → uint8_t
    Serial.print("integration "); Serial.print(sensor.integrationTime(), 3); Serial.print(" s"); Serial.println();// Integration time, () → float s
    Serial.print("normalized "); Serial.print(sensor.normalized(sensor.readGreen()), 0); Serial.println();// Counts per gain-second, (counts) → float
    ok = sensor.setThresholds(1000, 100000);                        // Set the threshold window, (low, high) → bool
                                                                    // 20-bit counts
    uint32_t lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    Serial.print("window "); Serial.print((unsigned long)(lo)); Serial.print(" - "); Serial.print((unsigned long)(hi)); Serial.println();
    ok = sensor.setInterruptChannel(Apds9250Full::CHANNEL_GREEN);   // Choose the monitored channel, (channel) → bool
                                                                    // CHANNEL_IR, _GREEN, _RED or _BLUE
    ok = sensor.setPersistence(2);                                  // Set the persistence, (count) → bool
                                                                    // 1–16 consecutive events
    ok = sensor.setVariance(64);                                    // Set the variation threshold, (counts) → bool
                                                                    // 8 … 1024, power of two
    sensor.enableInterrupt(Apds9250Full::SOURCE_VARIATION);         // Enable the interrupt, (source) → void
                                                                    // SOURCE_THRESHOLD or SOURCE_VARIATION
    delay(2000);
    Serial.print("poll 0x"); Serial.print((unsigned)(sensor.pollInterrupt()), HEX); Serial.println();// Read and clear MAIN_STATUS, () → uint8_t
                                                                    // 0x20 power-on, 0x10 interrupt, 0x08 data
    sensor.disableInterrupt();                                      // Disable the interrupt, () → void
    Serial.print("status 0x"); Serial.print((unsigned)(sensor.status()), HEX); Serial.println();// Raw MAIN_STATUS, () → uint8_t
    sensor.softReset();                                             // Software reset, () → void
                                                                    // all registers to defaults, sensor in standby
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
