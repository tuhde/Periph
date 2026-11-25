#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Pca9685.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);

    Pca9685Full pwm(connection);                      // Create PCA9685 driver, (connection)
    stdio_init_all();
    sleep_ms(2000);

    pwm.set_duty(0, 0.25f);                               // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → bool
    pwm.set_pwm(1, 409, 1228);                            // Phase-shifted PWM, (channel, on 0–4095, off 0–4095) → bool
    // 10 % delay, 20 % duty
    uint16_t on = 0, off = 0;
    pwm.get_pwm(1, on, off);                              // Read the ON/OFF counts, (channel, &on, &off) → bool
    printf("%d\n", (int)(on));
    printf("%d\n", (int)(off));
    pwm.set_full_on(2);                                   // Hold a channel high, (channel) → bool
    pwm.set_full_off(2);                                  // Hold a channel low, (channel) → bool
    pwm.set_all_duty(0.1f);                               // Same duty on all 16 channels, (duty 0.0–1.0) → bool
    pwm.set_all_pwm(0, 2048);                             // Same ON/OFF counts on all channels, (on, off) → bool
    pwm.set_all_duty(0.0f);
    pwm.set_frequency(50.0f);                             // PWM frequency, (hz 24–1526, osc_hz = 25 MHz) → bool
    printf("%d\n", (int)(pwm.get_frequency()));           // Programmed PWM frequency, (osc_hz = 25 MHz) → float Hz
    pwm.set_pulse_width(15, 0.0015f);                     // Servo pulse width, (channel, seconds) → bool
    // 1.5 ms at 50 Hz = servo centre
    sleep_ms(1000);
    pwm.sleep();                                          // Low-power mode, outputs stop, () → void
    pwm.wake();                                           // Wake and restart running channels, () → void
    pwm.restart();                                        // Datasheet restart sequence, () → void
    pwm.set_output_mode(false, false, Pca9685Full::OFF_LOW);// MODE2 output driver, (open_drain, invert, off_state) → bool
    pwm.set_update_on_ack(false);                         // Outputs change on STOP (false) or ACK (true), (enabled) → void
    pwm.set_allcall(false, 0x70);                         // LED All Call address, (enabled, address) → bool
    pwm.set_subaddress(1, 0x71, false);                   // LED Sub Call address, (index 1–3, address, enabled) → bool
    uint8_t mode1 = 0, mode2 = 0;
    pwm.read_mode(mode1, mode2);                          // Raw MODE1/MODE2, (&mode1, &mode2) → void
    printf("%d\n", (int)(mode1));
    printf("%d\n", (int)(mode2));
    // pwm.use_external_clock();  Sticky EXTCLK switch, () → void (needs a clock on EXTCLK)
    // Pca9685Full::software_reset(general_call);  Reset every PCA9685 on the bus, (Connection at address 0x00) → void
    pwm.reset();                                          // Write power-on defaults, () → void

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
