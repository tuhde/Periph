
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Pca9685.h"
#include <math.h>

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x40;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    Pca9685Full pwm(connection);                      // Create PCA9685 driver, (connection)

    // --- LED fader and servo sweep ---
    // Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
    // switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
    // all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
    // outputs preserved, the all-call and sub addresses, and finish with a reset.
    pwm.set_frequency(1000.0f);                           // 1 kHz PWM for LEDs, (hz) → bool
    for (int i = 0; i <= 100; i++) {
        pwm.set_duty(0, 0.5f - 0.5f * cosf(3.14159265f * i / 50.0f));// Fade up and down, (channel, duty) → bool
        vTaskDelay(pdMS_TO_TICKS(20));
    }
    pwm.set_pwm(1, 0, 1024);                              // 25 % duty, no delay, (channel, on, off) → bool
    pwm.set_pwm(2, 2048, 3072);                           // Same duty, shifted by half a period
    uint16_t on = 0, off = 0;
    pwm.get_pwm(2, on, off);                              // Read back the counts, (channel, &on, &off) → bool
    printf("%d\n", (int)(on));
    printf("%d\n", (int)(off));
    vTaskDelay(pdMS_TO_TICKS(1000));
    for (uint8_t ch = 0; ch < 16; ch++) {
        pwm.set_duty(ch, 1.0f);                           // Chase across all channels, (channel, duty) → bool
        vTaskDelay(pdMS_TO_TICKS(50));
        pwm.set_duty(ch, 0.0f);
    }
    pwm.set_all_duty(0.2f);                               // All channels together, (duty) → bool
    vTaskDelay(pdMS_TO_TICKS(500));
    pwm.set_all_pwm(0, 3072);                             // 75 % on all channels, (on, off) → bool
    vTaskDelay(pdMS_TO_TICKS(500));
    pwm.set_all_duty(0.0f);
    pwm.set_full_on(3);                                   // Constant high, (channel) → bool
    vTaskDelay(pdMS_TO_TICKS(500));
    pwm.set_full_off(3);                                  // Constant low, (channel) → bool
    pwm.set_frequency(50.0f);                             // Servo frequency, (hz) → bool
    for (int us = 1000; us <= 2000; us += 50) {
        pwm.set_pulse_width(15, us / 1e6f);               // Servo pulse 1–2 ms, (channel, seconds) → bool
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    printf("%d\n", (int)(pwm.get_frequency()));           // () → float Hz
    pwm.set_frequency(1000.0f);
    pwm.set_duty(4, 0.5f);
    pwm.set_output_mode(false, true, Pca9685Full::OFF_LOW);// Inverted output, (open_drain, invert, off_state) → bool
    vTaskDelay(pdMS_TO_TICKS(500));
    pwm.set_output_mode(true, false, Pca9685Full::OFF_HIGHZ);// Open-drain, high-Z when OE = 1
    vTaskDelay(pdMS_TO_TICKS(500));
    pwm.set_output_mode();                                // Back to totem-pole, not inverted
    pwm.set_update_on_ack(true);                          // Update on ACK, (enabled) → void
    pwm.set_update_on_ack(false);
    pwm.sleep();                                          // Outputs pause, PWM registers kept, () → void
    vTaskDelay(pdMS_TO_TICKS(500));
    pwm.wake();                                           // Outputs resume, () → void
    pwm.restart();                                        // Restart sequence, () → void
    pwm.set_allcall(true);                                // Respond to all-call, (enabled, address) → bool
    pwm.set_allcall(false);
    pwm.set_subaddress(1, 0x71, true);                    // Respond to sub address 1, (index, address, enabled) → bool
    pwm.set_subaddress(1, 0x71, false);
    uint8_t mode1 = 0, mode2 = 0;
    pwm.read_mode(mode1, mode2);                          // () → (mode1, mode2)
    printf("%d\n", (int)(mode1));
    printf("%d\n", (int)(mode2));
    pwm.reset();                                          // Back to power-on state, () → void

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
