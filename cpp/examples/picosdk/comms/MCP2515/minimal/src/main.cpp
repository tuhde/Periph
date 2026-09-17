#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "MCP2515.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 10000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                        // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    MCP2515Minimal mcp2515(connection);                                    // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Minimal
                                                                            // initialises with default 125 kbit/s at 8 MHz, Normal mode

    uint8_t payload[4] = { 0xDE, 0xAD, 0xBE, 0xEF };
    uint8_t tx_buf = mcp2515.send(0x123, payload, 4);                      // Send a CAN frame, (id=0x123, data, len=4) → uint8_t buf_index
                                                                            // returns 0/1/2 (buffer used) or 0xFF (none free)

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 100);                             // Poll for a received frame, (frame, timeout_ms=100) → bool
                                                                            // returns false on timeout, true on frame received

    printf("send_buf=%u recv=%d\n", tx_buf, got_frame ? 1 : 0);
    return 0;
}