#include <math.h>
#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Si1132.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, Si1132Minimal::I2C_ADDRESS);
    Si1132Full sensor(connection);                                  // Create Si1132 driver, (connection)
    sensor.init();                                                  // Initialize the chip, () → bool

    // --- UV and light monitor ---
    // Prints the calibration bytes, reads UV index, visible and infrared counts
    // once per second, shows the effect of gain 0/2/4 and of the high-range bits,
    // reads the temperature and VDD auxiliary channels, runs autonomous mode at
    // 0.5 s while polling the interrupt status, then restricts the channels to UV
    // only and finishes with pause and reset.
    uint8_t cal[12];
    sensor.read_calibration(cal);                                   // Read the factory calibration, (out[12]) → bool
    printf("calibration %02X %02X %02X %02X\n", (unsigned)(cal[0]), (unsigned)(cal[1]), (unsigned)(cal[2]), (unsigned)(cal[3]));
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    for (int i = 0; i < 20; i++) {
        sensor.read(uv, vis, ir);                                   // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
        printf("UV %.2f visible %lu infrared %lu\n", (double)(uv), (unsigned long)(vis), (unsigned long)(ir));
        sleep_ms(1000);
    }
    const uint8_t gains[3] = {0, 2, 4};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.set_gain(gains[i], gains[i]);                        // Set the ADC gain, (visible 0-7, infrared 0-7) → bool
        sensor.read(uv, vis, ir);
        printf("gain %lu visible %lu infrared %lu\n", (unsigned long)(gains[i]), (unsigned long)(vis), (unsigned long)(ir));
    }
    sensor.set_gain(0, 0);
    sensor.set_high_range(true, true);                              // Set the high signal range, (visible, infrared) → bool
    sensor.read(uv, vis, ir);
    printf("high range visible %lu infrared %lu\n", (unsigned long)(vis), (unsigned long)(ir));
    sensor.set_high_range(false, false);
    sensor.read_temperature_raw(aux);                               // Read die temperature, (raw&) → bool
    printf("temperature raw %lu\n", (unsigned long)(aux));
    sensor.read_vdd_raw(aux);                                       // Read supply voltage, (raw&) → bool
    printf("vdd raw %lu\n", (unsigned long)(aux));
    sensor.start_autonomous(0.5f);                                  // Start autonomous mode, (interval_s 0-2.05) → bool
    sensor.enable_interrupt();                                      // Enable the INT pin, () → bool
    for (int i = 0; i < 10; i++) {
        sleep_ms(500);
        uint8_t st = sensor.poll_interrupt();                       // Poll interrupt status, () → uint8_t
        if (st) {
            sensor.read_raw(vis, ir, aux);                          // Read the data registers, (visible&, infrared&, aux&) → bool
            printf("INT 0x%02X visible %lu\n", (unsigned)(st), (unsigned long)(vis));
        }
    }
    sensor.disable_interrupt();                                     // Disable the INT pin, () → bool
    sensor.pause();                                                 // Pause autonomous mode, () → bool
    sensor.set_channels(true, false, false);                        // Select channels, (uv, visible, infrared, aux=false) → bool
    printf("UV only %.2f\n", (double)(sensor.read_uv_index()));     // Read the UV index, () → float
    sensor.reset();                                                 // Reset the chip, () → bool
    return 0;
}
