#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "MCP2515.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 0;
    dev_cfg.clock_speed_hz  = 10000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);                              // Create SPI connection, (dev) → SPIConnectionESPIDF
    MCP2515Full mcp2515(connection);                                  // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Full
                                                                     // initialises with default 125 kbit/s at 8 MHz, Normal mode

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);           // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
    mcp2515.set_filter(0, 0x123, false);                              // Configure acceptance filter, (filter_num=0, id=0x123, extended=false) → void
    mcp2515.set_mask(0, 0x7FF, false);                                // Configure acceptance mask, (mask_num=0, mask=0x7FF, extended=false) → void
    mcp2515.set_rx_mode(0, 0x03);                                     // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
    mcp2515.set_one_shot(true);                                       // Set OSM in CANCTRL, (enable=true) → void

    uint8_t payload[4] = { 0xDE, 0xAD, 0xBE, 0xEF };
    uint8_t tx_buf = mcp2515.send_buffered(0x456, payload, 4, false, 1); // Send on a specific TX buffer, (id=0x456, data, len=4, extended=false, buf=1) → uint8_t buf_index
    check_true(tx_buf == 1, "send_buffered_to_buf1");

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 200);                        // Poll for a received frame, (frame, timeout_ms=200) → bool
    check_true(got_frame, "recv_in_loopback");
    if (got_frame) {
        check_true(frame.id == 0x456, "recv_id_matches");
        check_true(frame.dlc == 4, "recv_dlc_matches");
    }

    uint8_t tec = 0, rec = 0, eflg = 0;
    mcp2515.read_errors(tec, rec, eflg);                              // Read TEC, REC, EFLG, (out_tec, out_rec, out_eflg) → void
    check_true(true, "read_errors");

    mcp2515.abort_tx();                                               // Abort pending TX, () → void
    mcp2515.clear_overflow(0);                                        // Clear RX0OVR flag in EFLG, (buf=0) → void
    mcp2515.set_one_shot(false);                                      // Set OSM in CANCTRL, (enable=false) → void

    mcp2515.reset();                                                  // Issue SPI RESET, () → void
    check_true(true, "reset");

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_NORMAL);             // Switch operating mode, (mode=CANSTAT_OPMOD_NORMAL) → void
    check_true(mcp2515.get_mode() == _MCP2515Base::CANSTAT_OPMOD_NORMAL, "get_mode_normal"); // Read current operating mode, () → uint8_t OPMOD

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}