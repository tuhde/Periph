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

    // --- Loopback self-test: TX frames are routed straight back into RX ---
    // Lets us verify the SPI bus, register sequence, and frame format end-to-end
    // with no other CAN node attached. Switches the chip into LOOPBACK mode,
    // accepts all IDs, and disables retransmit so we see exactly one TX→RX
    // round-trip per heartbeat without automatic retries.
    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);           // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
    mcp2515.set_rx_mode(0, 0x03);                                     // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
    mcp2515.set_one_shot(false);                                      // Set OSM in CANCTRL, (enable=false) → void

    uint32_t counter = 0;
    while (1) {
        // --- Heartbeat frame: standard ID 0x001 + 4-byte uptime counter ---
        // The payload carries a monotonically increasing counter so the receiver
        // can confirm ordering. ID 0x001 is reserved for this demo.
        uint8_t payload[4] = {
            (uint8_t)(counter >> 24), (uint8_t)(counter >> 16),
            (uint8_t)(counter >> 8),  (uint8_t)(counter)
        };
        uint8_t buf = mcp2515.send(0x001, payload, 4);                // Send a CAN frame, (id=0x001, data=4 B counter, len=4) → uint8_t buf_index

        CanFrame frame;
        bool got = mcp2515.recv(frame, 100);                          // Poll for a received frame, (frame, timeout_ms=100) → bool

        if (got) {
            printf("RX id=0x%X dlc=%u data=", (unsigned)(frame.extended ? frame.id : (frame.id & 0x7FF)),
                   frame.dlc);
            for (uint8_t i = 0; i < frame.dlc; i++) printf("%02X ", frame.data[i]);
            printf("[%s%s]\n", frame.extended ? "EXT" : "STD", frame.rtr ? "/RTR" : "");
        }
        counter++;
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}