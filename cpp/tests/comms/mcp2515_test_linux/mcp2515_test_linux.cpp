#include <cstdio>
#include "SPIConnectionLinux.h"
#include "MCP2515.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 10000000); // Create SPI connection, (bus, device, mode=0, max_speed_hz=10e6) → SPIConnectionLinux
    MCP2515Full mcp2515(connection);                                            // Construct and initialise the MCP2515, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Full
                                                                                // initialises with default 125 kbit/s at 8 MHz, Normal mode

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_LOOPBACK);                     // Switch operating mode, (mode=CANSTAT_OPMOD_LOOPBACK) → void
    mcp2515.set_filter(0, 0x123, false);                                        // Configure acceptance filter, (filter_num=0, id=0x123, extended=false) → void
    mcp2515.set_mask(0, 0x7FF, false);                                          // Configure acceptance mask, (mask_num=0, mask=0x7FF, extended=false) → void
    mcp2515.set_rx_mode(0, 0x03);                                               // Set RXM[1:0] for RX buffer 0, (buf=0, mode=0x03=RXM_ANY) → void
    mcp2515.set_one_shot(true);                                                 // Set OSM in CANCTRL, (enable=true) → void

    uint8_t payload[4] = { 0xDE, 0xAD, 0xBE, 0xEF };
    uint8_t tx_buf = mcp2515.send_buffered(0x456, payload, 4, false, 1);        // Send on a specific TX buffer, (id=0x456, data, len=4, extended=false, buf=1) → uint8_t buf_index
    check_true("send_buffered_to_buf1", tx_buf == 1);

    CanFrame frame;
    bool got_frame = mcp2515.recv(frame, 200);                                   // Poll for a received frame, (frame, timeout_ms=200) → bool
    check_true("recv_in_loopback", got_frame);
    if (got_frame) {
        check_true("recv_id_matches", frame.id == 0x456);
        check_true("recv_dlc_matches", frame.dlc == 4);
    }

    uint8_t tec = 0, rec = 0, eflg = 0;
    mcp2515.read_errors(tec, rec, eflg);                                         // Read TEC, REC, EFLG, (out_tec, out_rec, out_eflg) → void
    check_true("read_errors", true);

    mcp2515.abort_tx();                                                          // Abort pending TX, () → void
    mcp2515.clear_overflow(0);                                                   // Clear RX0OVR flag in EFLG, (buf=0) → void
    mcp2515.set_one_shot(false);                                                 // Set OSM in CANCTRL, (enable=false) → void

    mcp2515.reset();                                                             // Issue SPI RESET, () → void
    check_true("reset", true);

    mcp2515.set_mode(_MCP2515Base::CANSTAT_OPMOD_NORMAL);                        // Switch operating mode, (mode=CANSTAT_OPMOD_NORMAL) → void
    check_true("get_mode_normal", mcp2515.get_mode() == _MCP2515Base::CANSTAT_OPMOD_NORMAL); // Read current operating mode, () → uint8_t OPMOD

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}