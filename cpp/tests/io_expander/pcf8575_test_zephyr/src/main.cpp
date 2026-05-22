#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/device.h>
#include <zephyr/drivers/i2c.h>
#include "PCF8575.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static const struct device* i2c_dev = DEVICE_DT_GET(I2C_NODE);

class ZephyrI2CTransport {
public:
    ZephyrI2CTransport(const struct device* dev, uint8_t addr) : dev(dev), addr(addr) {}

    void write(const uint8_t* data, size_t len) {
        i2c_write(dev, data, len, addr);
    }

    void read(uint8_t* buf, size_t len) {
        i2c_read(dev, buf, len, addr);
    }

private:
    const struct device* dev;
    uint8_t addr;
};

static int passed = 0;
static int failed = 0;

#define check_eq(label, got, expected) do { \
    if ((got) == (expected)) { printk("PASS %s\n", label); passed++; } \
    else { printk("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; } \
} while(0)

#define check_true(label, cond) do { \
    if (cond) { printk("PASS %s\n", label); passed++; } \
    else { printk("FAIL %s\n", label); failed++; } \
} while(0)

int main() {
    if (!device_is_ready(i2c_dev)) {
        printk("I2C device not ready\n");
        return 1;
    }

    ZephyrI2CTransport transport(i2c_dev, 0x20);
    PCF8575Minimal chip(transport);

    check_eq("init_shadow_0", chip._shadow[0], 0xFF);
    check_eq("init_shadow_1", chip._shadow[1], 0xFF);

    uint8_t port0 = chip.read_port(0);
    uint8_t port1 = chip.read_port(1);
    check_true("read_port_0_range", port0 <= 0xFF);
    check_true("read_port_1_range", port1 <= 0xFF);

    chip.write_port(0, 0xAA);
    check_eq("write_port_0_shadow", chip._shadow[0], 0xAA);
    chip.write_port(1, 0x55);
    check_eq("write_port_1_shadow", chip._shadow[1], 0x55);
    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);

    PCF8575Minimal::IOExpanderPin p0 = chip.pin(0);
    p0.mode(OUTPUT);
    p0.low();
    check_eq("pin_low_shadow",  chip._shadow[0] & 0x01, 0x00);
    p0.high();
    check_eq("pin_high_shadow", chip._shadow[0] & 0x01, 0x01);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed;
}