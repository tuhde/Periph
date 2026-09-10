// Auto-generated ESP-IDF example for LPS33HW (Complete).
// Mirrors the Arduino LPS33HW_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Lps33hw.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x5C,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LPS33HWFull chip(connection);  // Create LPS33HW driver, (connection)
    float p_lp, t_lp;
    uint8_t st, isrc, fsts;
    chip.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr 0-5, bdu, en_lpfp, lpfp_cfg 0/1, lc_en, sim) → void
    // sets CTRL_REG1 (ODR/BDU/LPF), RES_CONF (LPFP_CFG), CTRL_REG2 (SIM)
    chip.one_shot(p_lp, t_lp);                             // Trigger single measurement, () → (bool, &pressure_Pa, &temperature_C)
    // requires ODR=0; returns false on timeout
    chip.status();                                        // Read STATUS register, () → uint8_t
    // bit 0=P_DA, bit 1=T_DA, bit 4=P_OR, bit 5=T_OR
    chip.reset();                                         // Software reset via SWRESET, () → void
    // restores CTRL_REG1 / CTRL_REG2 to defaults
    chip.reboot();                                        // Reload factory trimming via BOOT, () → void
    // takes one ODR cycle to self-clear
    chip.set_pressure_offset(0.5f);                       // Apply one-point calibration, (offset_hPa) → void
    // 1 RPDS LSB = 1/16 hPa
    chip.set_autozero();                                  // Set AUTOZERO, () → void
    // current pressure is stored in REF_P
    chip.clear_autozero();                                // Clear AUTOZERO and reset REF_P, () → void
    chip.set_autorifp();                                  // Set AUTORIFP, () → void
    // next measurement value stored in RPDS
    chip.clear_autorifp();                                // Clear AUTORIFP and reset RPDS, () → void
    chip.configure_interrupt(true, false, false, false, LPS33HWFull::INT_S_DATA_SIGNALS, false, false);  // Route events to INT_DRDY, (drdy, f_fth, f_ovr, f_fss5, int_s 0-3, active_low, open_drain) → void
    // writes CTRL_REG3 (signal routing) and INTERRUPT_CFG (INT_S, active level, output mode)
    chip.configure_pressure_interrupt(true, false, 1050.0f, true);  // Configure pressure threshold interrupt, (high_en, low_en, threshold_hPa, latch) → void
    // arms INT_DRDY when pressure exceeds threshold; latched until INT_SOURCE read
    chip.interrupt_status();                              // Read INT_SOURCE, () → uint8_t
    // clears latched pressure interrupts
    chip.enable_fifo(LPS33HWFull::FIFO_MODE_FIFO, 16);    // Enable FIFO, (mode 0-7 excl. 5, watermark 0-31) → void
    // writes FIFO_CTRL and sets F_EN in CTRL_REG2
    chip.fifo_status();                                   // Read FIFO_STATUS, () → uint8_t
    // bit 7=FTH, bit 6=OVR, bits 5:0=FSS
    chip.disable_fifo();                                  // Disable FIFO and reset to Bypass, () → void
    chip.reset_lpf();                                     // Read LPFP_RES to flush transitory LPF state, () → void
    chip.pressure();                                      // Read pressure, () → float Pa
    chip.temperature();                                   // Read temperature, () → float °C
    vTaskDelay(pdMS_TO_TICKS(1000));
}