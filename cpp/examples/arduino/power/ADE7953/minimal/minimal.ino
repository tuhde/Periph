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
#include <Periph.h>

static const float VOLTAGE_GAIN = 251.0f;
static const float CURRENT_GAIN = 30.0f;

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection conn(Wire, TEST_ADDR);
    ADE7953Minimal ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);            // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    while (true) {
        float v = ade.voltage();                                      // Read bus voltage, () → V
        float i = ade.current();                                      // Read load current, () → A
        float p = ade.activePower();                                  // Read active power, () → W
        float e = ade.activeEnergy();                                 // Read active energy, () → Wh
        Serial.print("V=");    Serial.print(v, 2);
        Serial.print("  I=");  Serial.print(i, 3);
        Serial.print("  P=");  Serial.print(p, 2);
        Serial.print("  E=");  Serial.println(e, 4);
        delay(1000);
    }
}

void loop() {}
