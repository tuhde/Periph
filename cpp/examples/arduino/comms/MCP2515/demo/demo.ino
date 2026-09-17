#include <SPI.h>
#include "SPIConnection.h"
#include "MCP2515.h"

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(10000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, TEST_CS_PIN, settings);                   // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
MCP2515Full mcp2515(connection);                                        // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Full
                                                                        // initialises with default 125 kbit/s at 8 MHz, Normal mode

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    // --- Loopback self-test: TX frames are routed straight back into RX ---
    // Lets us verify the SPI bus, register sequence, and frame format end-to-end
    // with no other CAN node attached. Switches the chip into LOOPBACK mode,
    // accepts all IDs, and disables retransmit so we see exactly one TX→RX
    // round-trip per heartbeat without automatic retries.
    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);             // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
    mcp2515.set_rx_mode(0, 0x03);                                       // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
    mcp2515.set_one_shot(false);                                        // Set OSM in CANCTRL, (enable=false) → void

    uint32_t counter = 0;
    for (int n = 0; n < 5; n++) {
        // --- Heartbeat frame: standard ID 0x001 + 4-byte uptime counter ---
        // The payload carries a monotonically increasing counter so the receiver
        // can confirm ordering. ID 0x001 is reserved for this demo.
        uint8_t payload[4] = {
            (uint8_t)(counter >> 24), (uint8_t)(counter >> 16),
            (uint8_t)(counter >> 8),  (uint8_t)(counter)
        };
        uint8_t buf = mcp2515.send(0x001, payload, 4);                  // Send a CAN frame, (id=0x001, data=4 B counter, len=4) → uint8_t buf_index

        CanFrame frame;
        bool got = mcp2515.recv(frame, 100);                            // Poll for a received frame, (frame, timeout_ms=100) → bool

        if (got) {
            Serial.print("RX id=0x");
            if (frame.extended) {
                Serial.print(frame.id, HEX);
            } else {
                Serial.print(frame.id & 0x7FF, HEX);
            }
            Serial.print(" dlc=");
            Serial.print(frame.dlc);
            Serial.print(" data=");
            for (uint8_t i = 0; i < frame.dlc; i++) {
                Serial.print(frame.data[i], HEX);
                Serial.print(" ");
            }
            Serial.print(" [");
            Serial.print(frame.extended ? "EXT" : "STD");
            if (frame.rtr) Serial.print("/RTR");
            Serial.println("]");
        }
        counter++;
        delay(1000);
    }
}

void loop() {
}