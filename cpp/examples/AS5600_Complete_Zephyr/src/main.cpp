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

    // --- Status and magnet checks ---
    printk("magnet_detected=%d\n", as5600.is_magnet_detected());
    printk("magnet_too_strong=%d\n", as5600.is_magnet_too_strong());
    printk("magnet_too_weak=%d\n", as5600.is_magnet_too_weak());
    printk("status=0x%02X\n", as5600.status_byte());

    // --- Angle readings ---
    printk("angle=%.2f°\n", (double)as5600.angle());
    printk("angle_raw=%d\n", as5600.angle_raw());
    printk("raw_angle=%d\n", as5600.raw_angle());
    printk("raw_angle_degrees=%.2f°\n", (double)as5600.raw_angle_degrees());

    // --- Diagnostics ---
    printk("agc=%d\n", as5600.agc());
    printk("magnitude=%d\n", as5600.magnitude());

    // --- Position configuration (volatile) ---
    printk("zero_position=%d\n", as5600.zero_position());
    printk("max_position=%d\n", as5600.max_position());
    printk("max_angle=%d\n", as5600.max_angle());

    as5600.set_zero_position(0);
    as5600.set_max_position(4095);
    as5600.set_max_angle(2048);

    // --- Configure ---
    as5600.configure(AS5600Full::PM_NOM, 0, AS5600Full::OUTS_ANALOG, 0, 0, 0, false);

    // --- Burn count ---
    printk("burn_count=%d\n", as5600.burn_count());

    return 0;
}
