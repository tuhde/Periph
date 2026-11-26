#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Ccs811.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define CCS811_ADDR 0x5A

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, CCS811_ADDR);
    CCS811Minimal sensor(connection);                              // Create CCS811 driver, (connection) → void
    sensor.init();                                                 // Check HW_ID, start application, mode 1, () → bool
    while (true) {
        uint16_t eco2 = 0, tvoc = 0;
        bool ok = sensor.read(eco2, tvoc);                         // Wait for the next sample, (eco2_ppm&, tvoc_ppb&) → bool
        printk("eCO2 %u ppm TVOC %u ppb (ok=%u)\n", (unsigned)(eco2), (unsigned)(tvoc), (unsigned)(ok));
    }
    return 0;
}
