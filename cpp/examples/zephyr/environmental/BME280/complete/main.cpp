#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CTransportZephyr.h"
#include "BME280.h"

#ifndef BME280_I2C_NODE
#define BME280_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME280_ADDR
#define BME280_ADDR 0x76
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME280_I2C_NODE);
    I2CTransportZephyr transport(dev, BME280_ADDR);
    BME280Full bme(transport);                          // Create BME280 driver, (transport, spi=false)
    uint8_t cid = bme.chip_id();                        // Read chip ID, () → uint8_t
                                                         // returns 0x60 for BME280
    bme.configure(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::MODE_SLEEP, BME280Full::FILTER_OFF, BME280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → void
                                                         // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(BME280Full::OSRS_X4, BME280Full::OSRS_X2, BME280Full::OSRS_X1);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → void
                                                         // humidity update requires ctrl_meas write to latch
    bme.set_mode(BME280Full::MODE_FORCED);              // Set power mode, (mode 0/1/3) → void
    bme.set_filter(BME280Full::FILTER_4);               // Set IIR filter, (coeff 0–4) → void
                                                         // suppresses short-term pressure disturbances
    bme.set_standby(BME280Full::T_SB_125_MS);           // Set standby time, (t_sb 0–7) → void
                                                         // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
    uint8_t st = bme.status();                          // Read status register, () → uint8_t
    float t = bme.temperature();                        // Read temperature, () → float °C
    float p = bme.pressure();                           // Read pressure, () → float hPa
    float h = bme.humidity();                           // Read humidity, () → float %RH
    float alt = bme.altitude();                         // Compute altitude, (sea_level_hpa=1013.25) → float m
                                                         // uses barometric formula to convert pressure to metres
    float slp = bme.sea_level_pressure(alt);            // Compute sea-level pressure, (altitude_m) → float hPa
    float dp = bme.dew_point();                         // Compute dew point, () → float °C
                                                         // Magnus-Tetens approximation from current T and RH
    bme.reset();                                        // Soft reset chip, () → void
                                                         // re-reads calibration and re-applies configuration

    printk("T=%d C, P=%d hPa, RH=%d %%RH, alt=%d m, dp=%d C\n", (int)t, (int)p, (int)h, (int)alt, (int)dp);
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
