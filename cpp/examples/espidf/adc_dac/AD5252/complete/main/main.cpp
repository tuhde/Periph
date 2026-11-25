#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AD5252.h"

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
    dev_cfg.device_address = AD5252Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AD5252Full pot(connection);                         // Create AD5252 Full driver, (connection, rAb=10000.0 Ω, rWiper=75.0 Ω)
    pot.setWiper(0, 100);                               // Set wiper position, (channel 0–1, code 0–255) → bool
    // writes [instruction, code]; the instruction byte selects the channel
    uint8_t w = pot.getWiper(0);                        // Read wiper position, (channel 0–1) → uint8_t 0–255
    // sends the instruction alone, then reads one byte
    printf("wiper %d\n", (int)w);
    pot.setResistance(0, 5000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    // inverts R = D/256·RAB + RW to the nearest code
    float r = pot.getResistance(0);                     // Read W–B resistance, (channel 0–1) → float Ω
    // evaluates D/256·RAB + RW for the current code
    printf("R_WB %.1f ohm\n", (double)r);
    pot.setBoth(32, 224);                               // Write both wipers, (code0 0–255, code1 0–255) → void
    // two transactions, channel 0 then channel 1
    pot.setRatio(0, 0.25f);                             // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    // code = round(ratio × 256), clamped to 255
    float ratio = pot.getRatio(0);                      // Read divider ratio, (channel 0–1) → float
    // code / 256
    printf("ratio %.4f\n", (double)ratio);
    pot.setResistanceWa(1, 3000.0f);                    // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    // rheostat between W and A with B open
    float rwa = pot.getResistanceWa(1);                 // Read W–A resistance, (channel 0–1) → float Ω
    // (256 − D)/256·RAB + RW
    printf("R_WA %.1f ohm\n", (double)rwa);
    pot.increment(0);                                   // Step up one code, (channel=BOTH 0–1) → bool
    // quick command; BOTH addresses both channels
    pot.decrement(0);                                   // Step down one code, (channel=BOTH 0–1) → bool
    // quick command; saturates at 0
    pot.doubleCode(0);                                  // Raise by 6 dB, (channel=BOTH 0–1) → bool
    // quick command; the code doubles, saturating at 255
    pot.halve(0);                                       // Lower by 6 dB, (channel=BOTH 0–1) → bool
    // quick command; the code is halved
    pot.store();                                        // Store wipers in EEMEM, (channel=BOTH 0–1) → bool
    // quick command, then ACK polling (≈ 26 ms)
    pot.setBoth(1, 2);
    pot.restore();                                      // Restore wipers from EEMEM, (channel=BOTH 0–1) → bool
    // quick command followed by NOP
    printf("restored %d\n", (int)pot.getWiper(0));
    pot.writeEemem(4, 0x5A);                            // Write user EEMEM byte, (index 4–15, value 0–255) → bool
    // followed by ACK polling (≈ 26 ms)
    uint8_t e = pot.readEemem(4);                       // Read user EEMEM byte, (index 4–15) → uint8_t
    // pointer write, then a one-byte read
    printf("eemem4 %d\n", (int)e);
    float tol = pot.readTolerance(0);                   // Read stored RAB tolerance, (channel 0–1) → float %
    // sign-magnitude integer byte + decimal byte / 256
    printf("tolerance %.4f\n", (double)(tol));
    float ra = pot.rabActual(0);                        // Actual RAB of a channel, (channel 0–1) → float Ω
    // rAb × (1 + tolerance / 100)
    printf("rab_actual %.4f\n", (double)(ra));
    pot.nop();                                          // Send NOP, () → void
    // quick command 0x80; the chip returns to idle
    pot.setRab(50000.0f, 120.0f);                       // Change resistance model, (rAb Ω, rWiper=-1 Ω) → void
    // affects conversions only; no bus traffic
    pot.reset();                                        // Reset from EEMEM, () → bool
    // quick command 0xB8, then ACK polling
}
