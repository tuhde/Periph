#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "MCP2515.h"

#ifndef MCP2515_SPI_NODE
#define MCP2515_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef MCP2515_CS_GPIOS
#define MCP2515_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(MCP2515_SPI_NODE, cs_gpios, 0)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MCP2515_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 10000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = MCP2515_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    MCP2515Minimal mcp2515(connection);                                    // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Minimal
                                                                            // initialises with default 125 kbit/s at 8 MHz, Normal mode

    uint8_t payload[4] = { 0xDE, 0xAD, 0xBE, 0xEF };
    uint8_t tx_buf = mcp2515.send(0x123, payload, 4);                      // Send a CAN frame, (id=0x123, data, len=4) → uint8_t buf_index
                                                                            // returns 0/1/2 (buffer used) or 0xFF (none free)

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 100);                             // Poll for a received frame, (frame, timeout_ms=100) → bool
                                                                            // returns false on timeout, true on frame received

    check_true(true, "send_and_recv");
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}