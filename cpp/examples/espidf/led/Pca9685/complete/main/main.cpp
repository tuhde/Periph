
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Pca9685.h"

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
    vTaskDelay(pdMS_TO_TICKS(1000));
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

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
