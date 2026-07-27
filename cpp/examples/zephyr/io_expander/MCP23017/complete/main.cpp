#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "InputPinZephyr.h"
#include "MCP23017.h"

#ifndef MCP23017_I2C_NODE
#define MCP23017_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MCP23017_ADDR
#define MCP23017_ADDR 0x20
#endif

static const struct gpio_dt_spec int_gpio =
    GPIO_DT_SPEC_GET(DT_NODELABEL(mcp23017_int), gpios);

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MCP23017_I2C_NODE);
    InputPinZephyr intPin(int_gpio);                        // Create INT pin, (gpio_dt_spec)
    I2CConnectionZephyr connection(dev, MCP23017_ADDR, &intPin);  // Create I2C connection, (dev, addr, intPin)
    MCP23017Full mcp(connection);                           // Create MCP23017 driver, (connection, addr=0x20)

    mcp.configure_pullup(0, 0x3F);                        // Enable pull-ups on GPA0–GPA5, (port=0, mask=0x3F) → None
    check_true(true, "configure_pullup");

    mcp.configure_polarity(0, 0x00);                      // Set normal polarity PORTA, (port=0, mask=0x00) → None
    check_true(true, "configure_polarity");

    auto p7 = mcp.pin(7);                                  // GPA7 is output-only; get as output
    p7.mode(OUTPUT);                                      // Set pin direction, (mode=OUTPUT) → None
    p7.low();                                             // Drive GPA7 low, () → None

    mcp.write_port(0, 0x80);                              // Write GPA7 high, (port=0, mask=0x80) → None
    check_true(true, "write_port");

    mcp.set_default_value(0, 0x00);                       // Set DEFVAL for PORTA, (port=0, mask=0x00) → None

    mcp.onInterrupt([](uint8_t port, uint8_t status) {    // Subscribe to INT on both ports, (callback, intPin, mirror) → None
        printk("port %d changed: %02X\n", port, status);
    }, &intPin, /*mirror=*/true);
    check_true(true, "onInterrupt");

    mcp.offInterrupt(0);                                  // Unsubscribe PORTA, (port=0) → None
    check_true(true, "offInterrupt");

    uint8_t porta = mcp.read_port(0);                     // Read PORTA, (port=0) → uint8_t
    uint8_t portb = mcp.read_port(1);                      // Read PORTB, (port=1) → uint8_t
    printk("PORTA=%02X  PORTB=%02X\n", porta, portb);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}