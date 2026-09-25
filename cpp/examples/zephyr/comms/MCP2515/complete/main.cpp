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

    mcp2515.init(250, 8);                                                   // Re-run init sequence, (bitrate_kbps=250, osc_mhz=8) → void
                                                                            // switches to 250 kbit/s at 8 MHz and Config/Loopback/Normal as needed

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);                // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
                                                                            // routes TX frames back into RX for self-test without a bus

    mcp2515.set_filter(0, 0x123, false);                                   // Configure acceptance filter, (filter_num=0, id=0x123, extended=false) → void
                                                                            // must be in Config mode (driver switches in/out automatically)

    mcp2515.set_mask(0, 0x7FF, false);                                     // Configure acceptance mask, (mask_num=0, mask=0x7FF, extended=false) → void
                                                                            // 0x7FF accepts any 11-bit ID when filter is matched

    mcp2515.set_rx_mode(0, 0x03);                                          // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
                                                                            // RXM_ANY disables filtering for the buffer

    mcp2515.set_one_shot(true);                                            // Set OSM in CANCTRL, (enable=true) → void
                                                                            // disables automatic retransmission on arbitration loss / error

    uint8_t tx_buf = mcp2515.send_buffered(0x456, (const uint8_t[]){0x01, 0x02}, 2, false, 1); // Send on a specific TX buffer, (id, data, len, extended, buf=1) → uint8_t buf_index

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 100);                             // Poll for a received frame, (frame, timeout_ms=100) → bool

    uint8_t tec = 0, rec = 0, eflg = 0;
    mcp2515.read_errors(tec, rec, eflg);                                   // Read TEC, REC, EFLG, (out_tec, out_rec, out_eflg) → void
                                                                            // transmit error counter, receive error counter, error flags

    mcp2515.abort_tx();                                                    // Abort pending TX, () → void
                                                                            // sets ABAT in CANCTRL and waits for it to clear

    mcp2515.reset();                                                       // Issue SPI RESET, () → void
                                                                            // chip returns to defaults; init() must be called again

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_NORMAL);                 // Switch operating mode, (mode=CANSTAT_OPMOD_NORMAL) → void
                                                                            // returns to active bus mode

    return 0;
}