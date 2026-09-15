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
    MCP2515Minimal mcp2515(connection);                               // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Minimal
                                                                     // initialises with default 125 kbit/s at 8 MHz, Normal mode

    uint8_t payload[4] = { 0xDE, 0xAD, 0xBE, 0xEF };
    uint8_t tx_buf = mcp2515.send(0x123, payload, 4);                 // Send a CAN frame, (id=0x123, data, len=4) → uint8_t buf_index
                                                                     // returns 0/1/2 (buffer used) or 0xFF (none free)

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 100);                        // Poll for a received frame, (frame, timeout_ms=100) → bool
                                                                     // returns false on timeout, true on frame received

    printf("send_buf=%u recv=%d\n", tx_buf, got_frame ? 1 : 0);
}