#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, TEST_ADDR);

    Pca9685Minimal pwm(connection);                   // Create PCA9685 driver, (connection)

    for (uint8_t step = 0; step <= 10; step++) {
        pwm.set_duty(0, step / 10.0f);                    // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → bool
        delay(200);
    }
    pwm.set_duty(0, 0.0f);

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
