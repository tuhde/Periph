#include <stdio.h>
#include <string.h>
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
    MCP2515Full mcp2515(connection);                                       // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Full
                                                                           // initialises with default 125 kbit/s at 8 MHz, Normal mode

    // --- Loopback self-test: TX frames are routed straight back into RX ---
    // Lets us verify the SPI bus, register sequence, and frame format end-to-end
    // with no other CAN node attached. Switches the chip into LOOPBACK mode,
    // accepts all IDs, and disables retransmit so we see exactly one TX→RX
    // round-trip per heartbeat without automatic retries.
    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);                // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
    mcp2515.set_rx_mode(0, 0x03);                                          // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
    mcp2515.set_one_shot(false);                                           // Set OSM in CANCTRL, (enable=false) → void

    uint32_t counter = 0;
    while (1) {
        // --- Heartbeat frame: standard ID 0x001 + 4-byte uptime counter ---
        // The payload carries a monotonically increasing counter so the receiver
        // can confirm ordering. ID 0x001 is reserved for this demo.
        uint8_t payload[4] = {
            (uint8_t)(counter >> 24), (uint8_t)(counter >> 16),
            (uint8_t)(counter >> 8),  (uint8_t)(counter)
        };
        uint8_t buf = mcp2515.send(0x001, payload, 4);                    // Send a CAN frame, (id=0x001, data=4 B counter, len=4) → uint8_t buf_index

        CanFrame frame;
        bool got = mcp2515.recv(frame, 100);                              // Poll for a received frame, (frame, timeout_ms=100) → bool

        if (got) {
            printf("RX id=0x%X dlc=%u data=", frame.extended ? frame.id : (frame.id & 0x7FF),
                   frame.dlc);
            for (uint8_t i = 0; i < frame.dlc; i++) printf("%02X ", frame.data[i]);
            printf("[%s%s]\n", frame.extended ? "EXT" : "STD", frame.rtr ? "/RTR" : "");
        }
        counter++;
        sleep_ms(1000);
    }
    return 0;
}