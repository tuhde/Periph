#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP23017.h"

#ifndef MCP23017_I2C_NODE
#define MCP23017_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MCP23017_ADDR
#define MCP23017_ADDR 0x20
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MCP23017_I2C_NODE);
    I2CTransportZephyr transport(dev, MCP23017_ADDR);
    MCP23017Full mcp(transport);                           // Create MCP23017 driver, (transport, addr=0x20)

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

    mcp.configure_interrupt(0, -1, [](uint8_t mask) {     // Enable INT on PORTA, (port=0, int_pin=-1, callback, mode='change') → None
        printk("PORTA changed: %02X\n", mask);
    }, "change", false);
    check_true(true, "configure_interrupt");

    mcp.stop_interrupt(0);                                // Disable INT on PORTA, (port=0) → None
    check_true(true, "stop_interrupt");

    uint8_t porta = mcp.read_port(0);                     // Read PORTA, (port=0) → uint8_t
    uint8_t portb = mcp.read_port(1);                      // Read PORTB, (port=1) → uint8_t
    printk("PORTA=%02X  PORTB=%02X\n", porta, portb);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}