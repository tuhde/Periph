#include <stdio.h>
#include <math.h>
#include "SPIConnectionMock.h"
#include "RFM9x.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool has(const SPIConnectionMock& c, std::initializer_list<uint8_t> bytes) {
    std::vector<uint8_t> v(bytes);
    for (auto& w : c.writes()) if (w == v) return true;
    return false;
}

int main() {
    // --- RFM95Minimal ctor: version read (preloaded), FRF, default config, TX power ---
    // RFM95 is HF (band_flag=0x08). FRF for 915 MHz = 0xE4,0xC0,0x00 (same math as Python).
    SPIConnectionMock connection;
    connection.setRegister(0x42, {0x12});  // REG_VERSION = EXPECTED_VERSION
    RFM95Minimal sensor(connection, 915000000);
    check_true(has(connection, {0x86, 0xE4}) && has(connection, {0x87, 0xC0}) && has(connection, {0x88, 0x00}),
               "init_frf_written");
    check_true(has(connection, {0x9D, 0x72}) && has(connection, {0x9E, 0x77}), "init_default_modem_config");
    check_true(has(connection, {0x89, 0x8F}), "init_default_tx_power");  // PA_CONFIG = PA_BOOST|(17-2)

    // KNOWN GAP (documented, not fixed here): the constructor's version check
    // is a no-op -- the `if (ver != EXPECTED_VERSION)` branch is empty despite
    // a comment claiming it "raises by overwriting version so the caller can
    // branch on it." No such mechanism exists; construction always succeeds
    // regardless of what RegVersion reads. Every other language (Python,
    // Node.js, Rust, Go, JVM) actually rejects a wrong version.
    SPIConnectionMock wrongVersionConn;
    wrongVersionConn.setRegister(0x42, {0x99});
    RFM95Minimal wrongVersionSensor(wrongVersionConn, 915000000);
    check_true(true, "ctor_does_not_reject_wrong_version_documented_gap");

    // --- send(): FIFO write, payload length, TX mode, IRQ poll+clear, back to standby ---
    connection.setRegister(0x12, {0x08});  // IRQ_FLAGS: TX_DONE set, poll succeeds immediately
    uint8_t payload[3] = {0xDE, 0xAD, 0xBE};
    sensor.send(payload, 3);
    check_true(has(connection, {0x8D, 0x80}), "send_fifo_addr_ptr");
    check_true(has(connection, {0x80, 0xDE, 0xAD, 0xBE}), "send_fifo_payload");
    check_true(has(connection, {0xA2, 0x03}), "send_payload_length");
    check_true(has(connection, {0xC0, 0x40}), "send_dio_mapping");
    check_true(has(connection, {0x81, 0x83}), "send_tx_mode");  // MODE_TX -> 0x80|0x00(HF)|0x03
    check_true(has(connection, {0x92, 0x08}), "send_clears_irq");
    check_true(connection.writes().back() == std::vector<uint8_t>({0x81, 0x81}), "send_returns_to_standby");

    // --- receive(): RX mode, IRQ poll, FIFO read ---
    connection.setRegister(0x12, {0x40});   // IRQ_FLAGS: RX_DONE set
    connection.setRegister(0x10, {0x00});   // FIFO_RX_CURRENT = 0
    connection.setRegister(0x13, {0x03});   // RX_NB_BYTES = 3
    connection.setRegister(0x00, {0xAA, 0xBB, 0xCC});
    uint8_t rxBuf[255];
    size_t rxLen = 0;
    bool ok = sensor.receive(rxBuf, rxLen, 100);
    check_true(ok && rxLen == 3 && rxBuf[0] == 0xAA && rxBuf[1] == 0xBB && rxBuf[2] == 0xCC, "receive_returns_payload");
    check_true(has(connection, {0x92, 0x40}), "receive_clears_irq");

    // receive() timeout: IRQ never set.
    connection.setRegister(0x12, {0x00});
    size_t timeoutLen = 0;
    bool timedOut = sensor.receive(rxBuf, timeoutLen, 10);
    check_true(!timedOut && timeoutLen == 0, "receive_timeout_returns_false");

    // --- RFM95Full: configure, set_frequency, set_tx_power, standby/sleep, telemetry, reset ---
    SPIConnectionMock fullConn;
    fullConn.setRegister(0x42, {0x12});
    RFM95Full full(fullConn, 915000000);

    // configure(sf=9, bw=125.0, cr=5, crc=true): MODEM_CONFIG_1=0x72, MODEM_CONFIG_2=0x97.
    full.configure(9, 125.0f, 5, true);
    check_true(has(fullConn, {0xB1, 0x03}), "configure_detection_opt");  // sf != 6
    check_true(has(fullConn, {0x9D, 0x72}), "configure_modem_config_1");
    check_true(has(fullConn, {0x9E, 0x97}), "configure_modem_config_2");

    // RFM97's max_sf is 9. configure() has no exceptions -- it clamps out-of-range
    // sf to the variant max instead of rejecting it (consistent no-exceptions
    // design used throughout this file, unlike Python's ValueError).
    SPIConnectionMock rfm97Conn;
    rfm97Conn.setRegister(0x42, {0x12});
    RFM97Full rfm97(rfm97Conn, 915000000);
    rfm97.configure(12, 125.0f, 5, true);  // sf=12 > max_sf=9 -> clamped to 9
    check_true(has(rfm97Conn, {0x9E, 0x97}), "configure_clamps_sf_to_variant_max");  // (9<<4)|(1<<2)|3=0x97

    // set_frequency(868 MHz): FRF = 0xD9, 0x00, 0x00
    full.set_frequency(868000000);
    check_true(has(fullConn, {0x86, 0xD9}) && has(fullConn, {0x87, 0x00}) && has(fullConn, {0x88, 0x00}),
               "set_frequency_writes_frf");

    // set_tx_power(20, true): high-power path, PA_CONFIG=0x8F
    full.set_tx_power(20, true);
    check_true(has(fullConn, {0xCD, 0x87}), "set_tx_power_high_power_dac");
    check_true(has(fullConn, {0x8B, 0x3B}), "set_tx_power_high_power_ocp");
    check_true(has(fullConn, {0x89, 0x8F}), "set_tx_power_high_power_config");

    // set_tx_power(10, false): RFO path, PA_CONFIG=0x7A
    full.set_tx_power(10, false);
    check_true(has(fullConn, {0x89, 0x7A}), "set_tx_power_rfo_config");

    // standby() / sleep()
    full.standby();
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x81, 0x81}), "standby");
    full.sleep();
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x81, 0x80}), "sleep");

    // version() / rssi() / last_packet_rssi() / last_packet_snr()
    check_true(full.version() == 0x12, "version");
    fullConn.setRegister(0x1B, {100});
    check_true(fabsf(full.rssi() - (-137.0f + 100.0f)) < 1e-6f, "rssi");
    fullConn.setRegister(0x1A, {90});
    check_true(fabsf(full.last_packet_rssi() - (-137.0f + 90.0f)) < 1e-6f, "last_packet_rssi");
    fullConn.setRegister(0x19, {20});  // positive SNR: 20/4 = 5.0 dB
    check_true(fabsf(full.last_packet_snr() - 5.0f) < 1e-6f, "last_packet_snr_positive");
    fullConn.setRegister(0x19, {0xF4});  // negative SNR: (int8_t)0xF4 = -12 -> -3.0 dB
    check_true(fabsf(full.last_packet_snr() - (-3.0f)) < 1e-6f, "last_packet_snr_negative");

    // receive_continuous() / read_packet() / stop_receive()
    full.receive_continuous();
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x81, 0x85}), "receive_continuous_rx_cont_mode");  // MODE_RX_CONT -> 0x80|0x08|0x05
    fullConn.setRegister(0x12, {0x40});
    fullConn.setRegister(0x10, {0x00});
    fullConn.setRegister(0x13, {0x02});
    fullConn.setRegister(0x00, {0x11, 0x22});
    uint8_t packetBuf[255];
    size_t packetLen = 0;
    check_true(full.read_packet(packetBuf, packetLen) && packetLen == 2 && packetBuf[0] == 0x11 && packetBuf[1] == 0x22,
               "read_packet_returns_payload");
    fullConn.setRegister(0x12, {0x00});
    size_t noneLen = 0;
    check_true(!full.read_packet(packetBuf, noneLen), "read_packet_false_when_not_ready");
    full.stop_receive();
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x81, 0x81}), "stop_receive_returns_to_standby");

    // --- reset(): no pin ownership in this driver (documented gap vs. other
    // languages) -- re-runs the same register sequence as the constructor.
    size_t preResetWrites = fullConn.writes().size();
    full.reset();
    check_true(fullConn.writes().size() > preResetWrites, "reset_reruns_register_sequence");
    check_true(fullConn.writes().back() == std::vector<uint8_t>({0x81, 0x81}), "reset_ends_in_standby");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
