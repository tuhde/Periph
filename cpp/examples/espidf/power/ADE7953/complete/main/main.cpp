#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADE7953.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x38,
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADE7953Full ade(connection, 251.0f, 30.0f);                            // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    printf("version: 0x%02X\n", ade.version());                            // Read silicon version, () → 0x..
                                                                             // returns the silicon revision
    printf("V=%.2f\n",   ade.voltage());                                   // Read bus voltage, () → V
                                                                             // converts raw VRMS to volts using voltage_gain
    printf("I_a=%.3f\n", ade.current());                                   // Read load current, () → A
                                                                             // converts raw IRMSA to amperes using current_gain
    printf("P_a=%.2f\n", ade.activePower());                               // Read active power, () → W
                                                                             // converts raw AWATT (instantaneous, 6.99 kHz) to watts
    printf("E_a=%.4f\n", ade.activeEnergy());                              // Read active energy, () → Wh
                                                                             // converts raw AENERGYA accumulated LSBs to watt-hours
    printf("PF=%.3f\n",  ade.powerFactor());                               // Read power factor, () → ratio
                                                                             // converts raw PFA (1 LSB = 2^-15) to a −1.0..+1.0 ratio
    printf("f=%.2f\n",    ade.lineFrequency());                             // Read line frequency, () → Hz

    ade.configureChannelB(30.0f);                                          // Set Channel B calibration, (current_gain_b) → none
    printf("I_b=%.3f\n", ade.currentB());                                   // Read Current Channel B, () → A

    ade.setActiveEnergyMode('a', 0);                                        // Set active-energy mode A, (channel, mode) → none
                                                                             // mode = 0 (normal) | 1 (positive-only) | 2 (absolute)
    ade.setPga('a', 1);                                                     // Write PGA Channel A, (channel, gain) → none
                                                                             // gain 1, 2, 4, 8, 16 (+22 valid only for Channel A)
    ade.setPhaseCalibration('a', 0.0f);                                     // Write phase calibration A, (channel, delay_s) → none
    ade.setGainCalibration(0x282, 0x400000);           // Write active-power gain A, (reg, value) → none
                                                                             // 0x400000 = unity; valid range 0x200000..0x600000
    ade.setOffsetCalibration(0x289, 0);              // Write active-power offset A, (reg, value) → none
                                                                             // signed 24-bit offset
    printf("checksum: 0x%08X\n", (unsigned)ade.checksum());                 // Read CRC/checksum, () → u32
    ade.enableChecksum(true);                                               // Enable CRC/checksum, (enabled) → none

    ade.configureOvervoltage(260.0f);                                       // Configure overvoltage, (threshold) → none
                                                                             // threshold in volts (same scale as voltage())
    ade.configureOvercurrent(40.0f);                                        // Configure overcurrent, (threshold) → none
                                                                             // threshold in amperes; applies to BOTH current channels

    ade.reset();                                                            // Software reset, () → none
                                                                             // waits 110 ms then re-runs the mandatory power-up sequence
}