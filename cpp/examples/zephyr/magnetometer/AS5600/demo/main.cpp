#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "AS5600.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AS5600_ADDR 0x36

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, AS5600_ADDR);
    AS5600Full as5600(transport);

    // --- Motor feedback monitor: read angle 10 times per second ---
    // AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
    // In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

    uint8_t prev_status = as5600.status_byte();

    for (int n = 0; n < 10; n++) {
        float a = as5600.angle();                    // Read absolute angle, () → float degrees
        uint16_t r = as5600.raw_angle();             // Read raw unscaled angle, () → int 0-4095
        uint8_t g = as5600.agc();                    // Read AGC value, () → int

        // --- Check for status changes (magnet inserted/removed) ---
        uint8_t status = as5600.status_byte();
        if (status != prev_status) {
            if (!as5600.is_magnet_detected()) {
                printk("[MAGNET REMOVED] MD=0\n");
            } else {
                printk("[MAGNET DETECTED] MD=1  MH=%d  ML=%d\n",
                       as5600.is_magnet_too_strong(), as5600.is_magnet_too_weak());
            }
            prev_status = status;
        }

        // --- AGC health check ---
        if (as5600.is_magnet_detected()) {
            const char* tag = "[OK]";
            if (g < 64 || g > 192) {
                tag = "[AGC low — magnet weak or too far]";
            }
            printk("angle=%.2f°  raw=%d  agc=%d  %s\n", (double)a, r, g, tag);
        }

        k_sleep(K_MSEC(100));
    }

    return 0;
}
