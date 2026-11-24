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
                                                                    // checks PART_ID, writes HW_KEY, UV coefficients and CHLIST
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    sensor.read(uv, vis, ir);                                       // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
    printf("UV %.2f visible %lu infrared %lu\n", (double)(uv), (unsigned long)(vis), (unsigned long)(ir));
    printf("uv index %.2f\n", (double)(sensor.read_uv_index()));    // Read the UV index, () → float
                                                                    // forced measurement, AUX_DATA / 100
    sensor.read_visible(vis);                                       // Read visible counts, (visible&) → bool
    sensor.read_infrared(ir);                                       // Read infrared counts, (infrared&) → bool
    sensor.read_raw(vis, ir, aux);                                  // Read the data registers, (visible&, infrared&, aux&) → bool
                                                                    // latest values, no measurement is started
    printf("raw %lu %lu %lu\n", (unsigned long)(vis), (unsigned long)(ir), (unsigned long)(aux));
    sensor.read_temperature_raw(aux);                               // Read die temperature, (raw&) → bool
                                                                    // auxiliary channel, relative only
    printf("temperature raw %lu\n", (unsigned long)(aux));
    sensor.read_vdd_raw(aux);                                       // Read supply voltage, (raw&) → bool
                                                                    // auxiliary channel with AUX_ADCMUX = 0x75
    printf("vdd raw %lu\n", (unsigned long)(aux));
    bool ok = sensor.set_channels(true, true, true);                // Select channels, (uv, visible, infrared, aux=false) → bool
                                                                    // uv and aux cannot be combined
    ok = sensor.set_gain(2, 2);                                     // Set the ADC gain, (visible 0-7, infrared 0-7) → bool
                                                                    // integration time x 2^n plus recovery count
    ok = sensor.set_gain(0, 0);
    ok = sensor.set_high_range(false, false);                       // Set the high signal range, (visible, infrared) → bool
                                                                    // true divides the sensitivity by 14.5
    ok = sensor.set_alignment(true, true);                          // Set the data alignment, (visible_low, infrared_low) → bool
                                                                    // true reports the least significant 16 bits
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    ok = sensor.set_uv_coefficients(ucoef);                         // Set the UV coefficients, (ucoef[4]) → bool
                                                                    // datasheet defaults for an open-air UV index
    uint8_t cal[12];
    ok = sensor.read_calibration(cal);                              // Read the factory calibration, (out[12]) → bool
                                                                    // GET_CAL then registers 0x22-0x2D
    ok = sensor.force();                                            // Start a forced measurement, () → bool
                                                                    // ALS_FORCE without waiting
    sleep_ms(50);
    printf("ready %lu\n", (unsigned long)(sensor.measurement_ready()));// Check measurement done, () → bool
                                                                    // IRQ_STATUS.ALS_INT
    uint8_t counter = 0, error = 0;
    sensor.read_response(counter, error);                           // Read the command response, (counter&, error&) → bool
                                                                    // error 0 = none
    printf("response %lu error %lu\n", (unsigned long)(counter), (unsigned long)(error));
    printf("poll 0x%02X\n", (unsigned)(sensor.poll_interrupt()));   // Poll interrupt status, () → uint8_t
                                                                    // reads IRQ_STATUS and clears it
    ok = sensor.start_autonomous(0.5f);                             // Start autonomous mode, (interval_s 0-2.05) → bool
                                                                    // MEAS_RATE in 31.25 us steps then ALS_AUTO
    ok = sensor.enable_interrupt();                                 // Enable the INT pin, () → bool
                                                                    // INT_OE and ALS_IE
    sleep_ms(1000);
    ok = sensor.disable_interrupt();                                // Disable the INT pin, () → bool
    ok = sensor.pause();                                            // Pause autonomous mode, () → bool
                                                                    // ALS_PAUSE
    ok = sensor.set_i2c_address(0x60);                              // Set the I2C address, (address 7-bit) → bool
                                                                    // I2C_ADDR parameter then BUSADDR, volatile
    ok = sensor.reset();                                            // Reset the chip, () → bool
                                                                    // RESET, waits, writes HW_KEY again
    printf("ok %lu\n", (unsigned long)(ok));
    return 0;
}
