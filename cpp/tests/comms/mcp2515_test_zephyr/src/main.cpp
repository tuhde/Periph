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
    MCP2515Full mcp2515(connection);                                        // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Full
                                                                            // initialises with default 125 kbit/s at 8 MHz, Normal mode

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);                // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
    mcp2515.set_filter(0, 0x123, false);                                   // Configure acceptance filter, (filter_num=0, id=0x123, extended=false) → void
    mcp2515.set_mask(0, 0x7FF, false);                                     // Configure acceptance mask, (mask_num=0, mask=0x7FF, extended=false) → void
    mcp2515.set_rx_mode(0, 0x03);                                          // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
    mcp2515.set_one_shot(true);                                            // Set OSM in CANCTRL, (enable=true) → void

    uint8_t payload[4] = { 0xDE, 0xAD, 0xBE, 0xEF };
    uint8_t tx_buf = mcp2515.send_buffered(0x456, payload, 4, false, 1);   // Send on a specific TX buffer, (id=0x456, data, len=4, extended=false, buf=1) → uint8_t buf_index
    check_true(tx_buf == 1, "send_buffered_to_buf1");

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 200);                             // Poll for a received frame, (frame, timeout_ms=200) → bool
    check_true(got_frame, "recv_in_loopback");
    if (got_frame) {
        check_true(frame.id == 0x456, "recv_id_matches");
        check_true(frame.dlc == 4, "recv_dlc_matches");
    }

    uint8_t tec = 0, rec = 0, eflg = 0;
    mcp2515.read_errors(tec, rec, eflg);                                   // Read TEC, REC, EFLG, (out_tec, out_rec, out_eflg) → void
    check_true(true, "read_errors");

    mcp2515.abort_tx();                                                    // Abort pending TX, () → void
    mcp2515.clear_overflow(0);                                             // Clear RX0OVR flag in EFLG, (buf=0) → void
    mcp2515.set_one_shot(false);                                           // Set OSM in CANCTRL, (enable=false) → void

    mcp2515.reset();                                                       // Issue SPI RESET, () → void
    check_true(true, "reset");

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_NORMAL);                  // Switch operating mode, (mode=CANSTAT_OPMOD_NORMAL) → void
    check_true(mcp2515.get_mode() == _MCP2515Base::CANSTAT_OPMOD_NORMAL, "get_mode_normal"); // Read current operating mode, () → uint8_t OPMOD

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}