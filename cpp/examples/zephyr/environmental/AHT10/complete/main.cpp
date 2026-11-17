#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AHT10.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AHT10_ADDR 0x38

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AHT10_ADDR);
    AHT10Full aht(connection);                                          // Create AHT10 driver, (connection, addr=0x38) → void

    printk("Calibrated:    %d\n",       aht.is_calibrated());          // Check calibration status, () → bool
                                                                       // reads CAL bit from status byte
    printk("Busy:          %d\n",       aht.is_busy());                // Check busy status, () → bool
                                                                       // reads BUSY bit from status byte

    float t, h;
    aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
                                                                       // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    printk("Temperature:   %.2f C\n",   (double)t);
    printk("Humidity:      %.2f %%RH\n", (double)h);

    printk("Temperature:   %.2f C\n",   (double)aht.temperature());    // Read temperature only, () → float °C
                                                                       // triggers full measurement, returns temperature_c
    printk("Humidity:      %.2f %%RH\n", (double)aht.humidity());      // Read humidity only, () → float %RH
                                                                       // triggers full measurement, returns humidity_pct

    uint32_t raw_rh, raw_t;
    aht.read_raw(raw_rh, raw_t);                                       // Read raw 20-bit values, (raw_humidity, raw_temperature) → bool
                                                                       // same measurement as read(), undecoded
    printk("%u %u\n", (unsigned)raw_rh, (unsigned)raw_t);

    printk("0x%02X\n", aht.read_status());                              // Read status byte, () → uint8_t
                                                                       // bit 7 BUSY, bits 6:5 MODE, bit 3 CAL
    printk("%.2f\n", (double)aht.dew_point());                          // Dew point, () → float °C
                                                                       // one fresh measurement, Magnus formula

    aht.soft_reset();                                                  // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms, re-runs the init command
    return 0;
}
