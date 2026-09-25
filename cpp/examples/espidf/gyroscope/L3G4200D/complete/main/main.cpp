#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "L3G4200D.h"

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
    dev_cfg.device_address = 0x68;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    L3G4200DFull chip(connection, false);                // Create L3G4200D driver, (connection, spi=false)
    uint8_t cid = chip.who_am_i();                        // Read WHO_AM_I, () → int
                                                           // returns 0xD3 for L3G4200D
    chip.configure(1, 0, 500);                            // Configure chip, (odr=1 [200 Hz], bandwidth=0, full_scale=500) → None
                                                           // sets CTRL_REG1 DR/BW and CTRL_REG4 FS
    chip.enable_axes(true, true, true);                  // Enable axes, (x, y, z) → None
                                                           // sets Xen/Yen/Zen in CTRL_REG1
    chip.set_full_scale(2000);                           // Set full scale, (full_scale 250/500/2000) → None
                                                           // updates FS[1:0] in CTRL_REG4
    bool ready = chip.data_ready();                      // Check data ready, () → bool
                                                           // returns STATUS_REG.ZYXDA
    uint8_t status = chip.status();                      // Read STATUS, () → int
                                                           // raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA)
    int8_t temp = chip.temperature();                    // Read temperature, () → int
                                                           // 8-bit signed relative count (−1 °C/digit)
    chip.enable_highpass(0, 4);                          // Enable high-pass, (mode 0–3, cutoff 0–9) → None
                                                           // sets HPen and HPM/HPCF; cutoff depends on ODR
    chip.disable_highpass();                             // Disable high-pass, () → None
                                                           // clears HPen in CTRL_REG5
    chip.set_interrupt(true, false, true, false, true, false, false, true);  // Configure INT1, (x_high, x_low, y_high, y_low, z_high, z_low, and_mode, latch) → None
                                                           // enable high events on x/y/z; latch until INT1_SRC read
    chip.set_threshold('x', 87.5);                       // Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → None
                                                           // converts dps to raw 15-bit value via sensitivity
    chip.set_duration(4, false);                         // Set INT1 duration, (samples 0–127, wait=false) → None
                                                           // INT1 must be true for `samples` ODR cycles before firing
    chip.set_data_ready_pin(true);                       // Route DRDY to INT2, (enable=true) → None
                                                           // sets I2_DRDY in CTRL_REG3
    chip.enable_fifo(2, 10);                             // Enable FIFO, (mode 2=stream, watermark=10) → None
    chip.disable_fifo();                                 // Disable FIFO, () → None
                                                           // bypass mode and clear FIFO_EN
    uint8_t samples = chip.fifo_samples();               // Read FIFO count, () → int
                                                           // FSS[4:0] from FIFO_SRC_REG
    chip.power_down();                                   // Enter power-down, () → None
                                                           // clears PD in CTRL_REG1
    chip.wake_up();                                      // Wake from power-down, () → None
                                                           // sets PD; previously enabled axes restored
    chip.sleep();                                        // Enter sleep mode, () → None
                                                           // PD=1, all axes off
    uint8_t int_src = chip.read_int_source();            // Read & clear INT1_SRC, () → int
                                                           // reading clears the interrupt-active bit
    float x, y, z;
    chip.angular_rate(x, y, z);                          // Read X/Y/Z angular rate, () → (float, float, float) rad/s
    printf("X=%.2f Y=%.2f Z=%.2f rad/s, T=%d, ready=%d, status=0x%02X, fifo=%u, src=0x%02X, cid=0x%02X\n",
           x, y, z, temp, ready, status, samples, int_src, cid);
    printf("===DONE: 0 passed, 0 failed===\n");
}
