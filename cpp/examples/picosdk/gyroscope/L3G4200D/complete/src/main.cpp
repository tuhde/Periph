#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "I2CConnectionPicoSDK.h"
#include "L3G4200D.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x68);
    L3G4200DFull chip(connection, /*spi=*/false);         // Create L3G4200D driver, (connection, spi=false)

    stdio_init_all();
    sleep_ms(2000);

    uint8_t cid = chip.who_am_i();                         // Read WHO_AM_I, () → int
                                                            // returns 0xD3 for L3G4200D
    chip.configure(1, 0, 500);                             // Configure chip, (odr=1 [200 Hz], bandwidth=0, full_scale=500) → None
                                                            // sets CTRL_REG1 DR/BW and CTRL_REG4 FS
    chip.enable_axes(true, true, true);                   // Enable axes, (x, y, z) → None
                                                            // sets Xen/Yen/Zen in CTRL_REG1
    chip.set_full_scale(2000);                            // Set full scale, (full_scale 250/500/2000) → None
                                                            // updates FS[1:0] in CTRL_REG4
    bool ready = chip.data_ready();                       // Check data ready, () → bool
                                                            // returns STATUS_REG.ZYXDA
    uint8_t status = chip.status();                       // Read STATUS, () → int
                                                            // raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA)
    int8_t temp = chip.temperature();                     // Read temperature, () → int
                                                            // 8-bit signed relative count (−1 °C/digit)
    chip.enable_highpass(0, 4);                           // Enable high-pass, (mode 0–3, cutoff 0–9) → None
                                                            // sets HPen and HPM/HPCF; cutoff depends on ODR
    chip.disable_highpass();                              // Disable high-pass, () → None
                                                            // clears HPen in CTRL_REG5
    chip.set_interrupt(true, false, true, false, true, false, false, true);  // Configure INT1, (x_high, x_low, y_high, y_low, z_high, z_low, and_mode, latch) → None
                                                            // enable high events on x/y/z; latch until INT1_SRC read
    chip.set_threshold('x', 87.5);                        // Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → None
                                                            // converts dps to raw 15-bit value via sensitivity
    chip.set_duration(4, false);                          // Set INT1 duration, (samples 0–127, wait=false) → None
                                                            // INT1 must be true for `samples` ODR cycles before firing
    chip.set_data_ready_pin(true);                        // Route DRDY to INT2, (enable=true) → None
                                                            // sets I2_DRDY in CTRL_REG3
    chip.enable_fifo(2, 10);                              // Enable FIFO, (mode 2=stream, watermark=10) → None
    chip.disable_fifo();                                  // Disable FIFO, () → None
                                                            // bypass mode and clear FIFO_EN
    uint8_t samples = chip.fifo_samples();                // Read FIFO count, () → int
                                                            // FSS[4:0] from FIFO_SRC_REG
    chip.power_down();                                    // Enter power-down, () → None
                                                            // clears PD in CTRL_REG1
    chip.wake_up();                                       // Wake from power-down, () → None
                                                            // sets PD; previously enabled axes restored
    chip.sleep();                                         // Enter sleep mode, () → None
                                                            // PD=1, all axes off
    uint8_t int_src = chip.read_int_source();             // Read & clear INT1_SRC, () → int
                                                            // reading clears the interrupt-active bit
    float x, y, z;
    chip.angular_rate(x, y, z);                           // Read X/Y/Z angular rate, () → (float, float, float) rad/s
    printf("X=%.2f Y=%.2f Z=%.2f rad/s, T=%d, ready=%d, status=0x%02X, fifo=%u, src=0x%02X, cid=0x%02X\n",
           x, y, z, temp, ready, status, samples, int_src, cid);
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
    return 0;
}
