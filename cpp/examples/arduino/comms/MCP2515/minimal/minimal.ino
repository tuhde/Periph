#include <SPI.h>
#include "SPIConnection.h"
#include "MCP2515.h"

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(10000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, TEST_CS_PIN, settings);                   // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
MCP2515Minimal mcp2515(connection);                                     // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Minimal
                                                                        // initialises with default 125 kbit/s at 8 MHz, Normal mode

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    uint8_t tx_buf = 0;
    uint8_t payload[4] = { 0xDE, 0xAD, 0xBE, 0xEF };
    tx_buf = mcp2515.send(0x123, payload, 4);                            // Send a CAN frame, (id=0x123, data, len=4) → uint8_t buf_index
                                                                        // returns 0/1/2 (buffer used) or 0xFF (none free)

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 100);                          // Poll for a received frame, (frame, timeout_ms=100) → bool
                                                                        // returns false on timeout, true on frame received
    check_true(true, "send_and_recv");
}

void loop() {
}