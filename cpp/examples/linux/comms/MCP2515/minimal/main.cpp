#include <cstdio>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "MCP2515.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 10000000); // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=10e6) → SPIConnectionLinux
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