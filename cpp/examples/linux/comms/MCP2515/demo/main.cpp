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
    MCP2515Full mcp2515(connection);                                          // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Full
                                                                              // initialises with default 125 kbit/s at 8 MHz, Normal mode

    // --- Loopback self-test: TX frames are routed straight back into RX ---
    // Lets us verify the SPI bus, register sequence, and frame format end-to-end
    // with no other CAN node attached. Switches the chip into LOOPBACK mode,
    // accepts all IDs, and disables retransmit so we see exactly one TX→RX
    // round-trip per heartbeat without automatic retries.
    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);                  // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
    mcp2515.set_rx_mode(0, 0x03);                                            // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
    mcp2515.set_one_shot(false);                                             // Set OSM in CANCTRL, (enable=false) → void

    uint32_t counter = 0;
    for (int n = 0; n < 5; n++) {
        // --- Heartbeat frame: standard ID 0x001 + 4-byte uptime counter ---
        // The payload carries a monotonically increasing counter so the receiver
        // can confirm ordering. ID 0x001 is reserved for this demo.
        uint8_t payload[4] = {
            (uint8_t)(counter >> 24), (uint8_t)(counter >> 16),
            (uint8_t)(counter >> 8),  (uint8_t)(counter)
        };
        uint8_t buf = mcp2515.send(0x001, payload, 4);                       // Send a CAN frame, (id=0x001, data=4 B counter, len=4) → uint8_t buf_index
        printf("TX counter=%u on buffer %u\n", (unsigned)counter, buf);

        CanFrame frame;
        bool got = mcp2515.recv(frame, 100);                                 // Poll for a received frame, (frame, timeout_ms=100) → bool

        if (got) {
            printf("RX id=0x%X dlc=%u data=", frame.extended ? frame.id : (frame.id & 0x7FF),
                   frame.dlc);
            for (uint8_t i = 0; i < frame.dlc; i++) printf("%02X ", frame.data[i]);
            printf("[%s%s]\n", frame.extended ? "EXT" : "STD", frame.rtr ? "/RTR" : "");
        }
        counter++;
        usleep(1000000);
    }
    return 0;
}