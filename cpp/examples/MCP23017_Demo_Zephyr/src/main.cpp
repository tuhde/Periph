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

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MCP23017_I2C_NODE);
    I2CTransportZephyr transport(dev, MCP23017_ADDR);
    MCP23017Full mcp(transport);                          // Create MCP23017 driver, (transport, addr=0x20)

    mcp.configure_pullup(1, 0x7F);                        // Enable pull-ups on GPB0–GPB6, (port=1, mask=0x7F) → None
    printk("=== Knight Rider scanner with button override ===\n");
    printk("  pos  btn_msk  out_mask\n");

    for (int step = 0; step < 40; step++) {
        uint8_t port_b = mcp.read_port(1);                  // Read GPB0–GPB6 buttons, (port=1) → uint8_t 0–127
        uint8_t btn_mask = (~port_b) & 0x7F;

        int direction = (step / 7) % 2 == 0 ? 1 : -1;
        int pos = step % 7;
        if (direction == -1) pos = 6 - pos;

        uint8_t scanner = 1 << pos;
        uint8_t output = btn_mask | scanner;

        mcp.write_port(0, output);                         // Write PORTA outputs, (port=0, mask) → None

        printk("  %d     %02X        %02X\n", pos, btn_mask, output);
        k_sleep(K_MSEC(100));
    }

    mcp.write_port(0, 0x00);                              // Clear all outputs, (port=0, mask=0x00) → None
    printk("=== Done ===\n");
    return 0;
}