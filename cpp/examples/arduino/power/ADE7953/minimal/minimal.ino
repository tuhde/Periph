#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <Arduino.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "ADE7953.h"

static const float VOLTAGE_GAIN = 251.0f;
static const float CURRENT_GAIN = 30.0f;

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection conn(Wire, TEST_ADDR);
    ADE7953Minimal ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);            // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    while (true) {
        float v = ade.voltage();                                      // Read bus voltage, () → V
        float i = ade.current();                                      // Read load current, () → A
        float p = ade.activePower();                                  // Read active power, () → W
        float e = ade.activeEnergy();                                 // Read active energy, () → Wh
        Serial.printf("V=%.2f  I=%.3f  P=%.2f  E=%.4f\n", v, i, p, e);
        delay(1000);
    }
}

void loop() {}