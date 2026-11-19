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

    Pca9685Full pwm(connection);                      // Create PCA9685 driver, (connection)

    pwm.set_duty(0, 0.25f);                               // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → bool
    pwm.set_pwm(1, 409, 1228);                            // Phase-shifted PWM, (channel, on 0–4095, off 0–4095) → bool
    // 10 % delay, 20 % duty
    uint16_t on = 0, off = 0;
    pwm.get_pwm(1, on, off);                              // Read the ON/OFF counts, (channel, &on, &off) → bool
    Serial.println((int)(on));
    Serial.println((int)(off));
    pwm.set_full_on(2);                                   // Hold a channel high, (channel) → bool
    pwm.set_full_off(2);                                  // Hold a channel low, (channel) → bool
    pwm.set_all_duty(0.1f);                               // Same duty on all 16 channels, (duty 0.0–1.0) → bool
    pwm.set_all_pwm(0, 2048);                             // Same ON/OFF counts on all channels, (on, off) → bool
    pwm.set_all_duty(0.0f);
    pwm.set_frequency(50.0f);                             // PWM frequency, (hz 24–1526, osc_hz = 25 MHz) → bool
    Serial.println((int)(pwm.get_frequency()));           // Programmed PWM frequency, (osc_hz = 25 MHz) → float Hz
    pwm.set_pulse_width(15, 0.0015f);                     // Servo pulse width, (channel, seconds) → bool
    // 1.5 ms at 50 Hz = servo centre
    delay(1000);
    pwm.sleep();                                          // Low-power mode, outputs stop, () → void
    pwm.wake();                                           // Wake and restart running channels, () → void
    pwm.restart();                                        // Datasheet restart sequence, () → void
    pwm.set_output_mode(false, false, Pca9685Full::OFF_LOW);// MODE2 output driver, (open_drain, invert, off_state) → bool
    pwm.set_update_on_ack(false);                         // Outputs change on STOP (false) or ACK (true), (enabled) → void
    pwm.set_allcall(false, 0x70);                         // LED All Call address, (enabled, address) → bool
    pwm.set_subaddress(1, 0x71, false);                   // LED Sub Call address, (index 1–3, address, enabled) → bool
    uint8_t mode1 = 0, mode2 = 0;
    pwm.read_mode(mode1, mode2);                          // Raw MODE1/MODE2, (&mode1, &mode2) → void
    Serial.println((int)(mode1));
    Serial.println((int)(mode2));
    // pwm.use_external_clock();  Sticky EXTCLK switch, () → void (needs a clock on EXTCLK)
    // Pca9685Full::software_reset(general_call);  Reset every PCA9685 on the bus, (Connection at address 0x00) → void
    pwm.reset();                                          // Write power-on defaults, () → void

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
