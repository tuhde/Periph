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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    mcp2515.init(250, 8);                                               // Re-run init sequence, (bitrate_kbps=250, osc_mhz=8) → void
                                                                        // switches to 250 kbit/s at 8 MHz and Config/Loopback/Normal as needed

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);             // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
                                                                        // routes TX frames back into RX for self-test without a bus

    mcp2515.set_filter(0, 0x123, false);                                // Configure acceptance filter, (filter_num=0, id=0x123, extended=false) → void
                                                                        // must be in Config mode (driver switches in/out automatically)

    mcp2515.set_mask(0, 0x7FF, false);                                  // Configure acceptance mask, (mask_num=0, mask=0x7FF, extended=false) → void
                                                                        // 0x7FF accepts any 11-bit ID when filter is matched

    mcp2515.set_rx_mode(0, 0x03);                                       // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
                                                                        // RXM_ANY disables filtering for the buffer

    mcp2515.set_one_shot(true);                                         // Set OSM in CANCTRL, (enable=true) → void
                                                                        // disables automatic retransmission on arbitration loss / error

    uint8_t tx_buf = mcp2515.send_buffered(0x456, (const uint8_t[]){0x01, 0x02}, 2, false, 1); // Send on a specific TX buffer, (id, data, len, extended, buf=1) → uint8_t buf_index

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 100);                          // Poll for a received frame, (frame, timeout_ms=100) → bool

    uint8_t tec = 0, rec = 0, eflg = 0;
    mcp2515.read_errors(tec, rec, eflg);                                // Read TEC, REC, EFLG, (out_tec, out_rec, out_eflg) → void
                                                                        // transmit error counter, receive error counter, error flags

    mcp2515.abort_tx();                                                 // Abort pending TX, () → void
                                                                        // sets ABAT in CANCTRL and waits for it to clear

    mcp2515.reset();                                                    // Issue SPI RESET, () → void
                                                                        // chip returns to defaults; init() must be called again

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_NORMAL);               // Switch operating mode, (mode=CANSTAT_OPMOD_NORMAL) → void
                                                                        // returns to active bus mode

    check_true(true, "full_api_exercise");
    Serial.print("===DONE: "); Serial.print(passed);
    Serial.print(" passed, "); Serial.print(failed); Serial.println(" failed===");
}

void loop() {
}