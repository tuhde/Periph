// Auto-generated ESP-IDF example for LTC4151 (Full).
// Mirrors the Arduino LTC4151_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LTC4151.h"

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
    dev_cfg.device_address = 0x6A;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    float f = 0;
    bool ok = false;
    LTC4151Full::Readings r;

    printf("voltage %.3f V\n", ltc.voltage());                          // Read supply voltage, () → float V
                                                                        // VIN code × 25 mV
    printf("current %.4f A\n", ltc.current());                          // Read load current, () → float A
                                                                        // sense voltage / r_sense
    printf("power %.3f W\n", ltc.power());                              // Read load power, () → float W
                                                                        // one block read of SENSE and VIN
    printf("sense voltage %.5f V\n", ltc.sense_voltage());              // Read SENSE+ − SENSE−, () → float V
                                                                        // code × 20 µV
    printf("adin %.4f V\n", ltc.adin_voltage());                        // Read ADIN pin voltage, () → float V
                                                                        // code × 0.5 mV
    ltc.read_all(r);                                                    // Consistent read of all channels, (out Readings) → void
                                                                        // one block read of registers 0x00–0x05
    printf("vin %.2f V, sense %.5f V, current %.4f A, power %.3f W, adin %.4f V\n", r.vin, r.sense_v, r.current, r.power, r.adin);
    printf("raw VIN code %u\n", (unsigned)ltc.read_raw(LTC4151Full::VIN));  // Read raw 12-bit code, (channel 0–2) → uint16_t
                                                                        // SENSE = 0, VIN = 1, ADIN = 2

    ltc.set_sense_resistor(0.01f);                                      // Change sense resistor, (ohms) → bool
                                                                        // false (and no change) for ohms <= 0
    printf("sense resistor %.4f ohm\n", ltc.get_sense_resistor());      // Read sense resistor, () → float Ω
    ltc.set_sense_resistor(0.02f);                                      // Restore 20 mΩ

    ok = ltc.snapshot(LTC4151Full::SENSE, f);                           // One snapshot conversion, (channel 0–2, out V) → bool
                                                                        // returns to continuous scan afterwards; false on timeout
    printf("snapshot ok %u, sense %.5f V\n", (unsigned)ok, f);
    printf("snapshot mode %u\n", (unsigned)ltc.is_snapshot_mode());     // Snapshot mode active, () → bool
    printf("VIN busy %u\n", (unsigned)ltc.is_busy(LTC4151Full::VIN));   // Channel busy, (channel 0–2) → bool
                                                                        // BUSY bit of the channel low register
    ltc.set_continuous();                                               // Back to continuous scan, () → void

    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
                                                                        // CONTROL.G2 read-modify-write
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
                                                                        // CONTROL.G3 read-modify-write
    printf("control 0x%02X\n", (unsigned)ltc.read_control());           // Read CONTROL, () → uint8_t
    ltc.write_control(0x0C);                                            // Write CONTROL, (value) → void
                                                                        // test mode bit 4 is always masked to 0
    printf("temperature %.2f C\n", LTC4151Full::temperature_from_adin(900, 1200));  // NTC example circuit, (adin_code, vin_code) → float °C
    printf("done\n");

    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
