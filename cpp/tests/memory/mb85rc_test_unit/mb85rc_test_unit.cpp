#include <stdio.h>
#include <string.h>
#include "I2CConnectionMock.h"
#include "MB85RC.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionMock connection(2);
    connection.setAddressWidth(2);
    MB85RCFull fram(connection);
    check_true(connection.writes().empty(), "init_no_traffic");
    check_true(fram.size() == 32768, "size");

    connection.setRegister(0x1234, {0x42});
    check_true(fram.read_byte(0x1234) == 0x42, "read_byte");
    check_true(connection.writes().back().size() == 2 &&
               connection.writes().back()[0] == 0x12 && connection.writes().back()[1] == 0x34,
               "read_byte_address_bytes");

    check_true(fram.write_byte(0x0010, 0x99), "write_byte_ok");
    check_true(connection.registers().at(0x0010) == 0x99, "write_byte");
    const auto& w = connection.writes().back();
    check_true(w.size() == 3 && w[0] == 0x00 && w[1] == 0x10 && w[2] == 0x99, "write_byte_frame");

    MB85RCMinimal minimal(connection);
    check_true(minimal.read_byte(0x1234) == 0x42, "minimal_class");
    size_t before = connection.writes().size();
    check_true(fram.read_byte(0x8000) == 0, "read_byte_range");
    check_true(!fram.write_byte(0x8000, 1), "write_byte_range");
    check_true(connection.writes().size() == before, "range_no_traffic");

    // Chunking: 70 bytes at max_chunk=30 -> 30 + 30 + 10.
    check_true(fram.max_chunk() == 30, "max_chunk_default");
    uint8_t data[70];
    for (int i = 0; i < 70; i++) data[i] = (uint8_t)i;
    before = connection.writes().size();
    check_true(fram.write(0x0100, data, 70), "write_ok");
    const auto& ws = connection.writes();
    check_true(ws.size() - before == 3, "write_chunk_count");
    check_true(ws[before].size() == 32 && ws[before][0] == 0x01 && ws[before][1] == 0x00 &&
               ws[before][2] == 0 && ws[before][31] == 29, "write_chunk_0");
    check_true(ws[before + 1].size() == 32 && ws[before + 1][0] == 0x01 &&
               ws[before + 1][1] == 30 && ws[before + 1][2] == 30, "write_chunk_1");
    check_true(ws[before + 2].size() == 12 && ws[before + 2][1] == 60 &&
               ws[before + 2][11] == 69, "write_chunk_2");
    uint8_t back[70];
    before = connection.writes().size();
    check_true(fram.read(0x0100, back, 70), "read_ok");
    check_true(memcmp(back, data, 70) == 0, "read_roundtrip");
    check_true(connection.writes().size() - before == 3, "read_chunk_count");
    check_true(connection.writes()[before + 1].size() == 2 && connection.writes()[before + 1][1] == 30,
               "read_chunk_addr");

    fram.set_max_chunk(8);
    before = connection.writes().size();
    uint8_t zeros[20] = {0};
    fram.write(0x0200, zeros, 20);
    check_true(connection.writes().size() - before == 3, "custom_max_chunk");
    fram.set_max_chunk(100);
    check_true(fram.max_chunk() == 32, "max_chunk_clamped");
    fram.set_max_chunk(30);

    // Fill.
    check_true(fram.fill(0x0300, 65, 0xAB), "fill_ok");
    bool all = true;
    for (int i = 0; i < 65; i++) all = all && connection.registers().at(0x0300 + i) == 0xAB;
    check_true(all, "fill");
    check_true(connection.registers().find(0x0300 + 65) == connection.registers().end(), "fill_end");
    fram.fill(0x0300, 4);
    check_true(connection.registers().at(0x0300) == 0 && connection.registers().at(0x0303) == 0,
               "fill_default_zero");

    // Range rules.
    uint8_t two[2] = {1, 2};
    check_true(fram.write(0x7FFE, two, 2), "write_last_bytes");
    uint8_t rb[2] = {0, 0};
    check_true(fram.read(0x7FFE, rb, 2) && rb[0] == 1 && rb[1] == 2, "read_last_bytes");
    check_true(!fram.write(0x7FFF, two, 2), "write_wrap_rejected");
    uint8_t big[0x20];
    check_true(!fram.read(0x7FF0, big, 0x20), "read_wrap_rejected");
    check_true(!fram.fill(0x7FFF, 2), "fill_wrap_rejected");
    check_true(fram.read(0x0000, big, 0), "read_zero_length");

    // Device ID with A2A1A0 = 3 -> address word 0xA6.
    I2CConnectionMock idConn;
    idConn.setRegister(0xA6, {0x00, 0xA5, 0x10});
    uint16_t mfr = 0; uint8_t dens = 0, prod = 0;
    fram.read_device_id(idConn, &mfr, &dens, &prod, 0x53);
    check_true(mfr == 0x00A && dens == 0x5 && prod == 0x10, "read_device_id");
    check_true(idConn.writes().back().size() == 1 && idConn.writes().back()[0] == 0xA6,
               "read_device_id_word");
    idConn.setRegister(0xA0, {0x00, 0xA5, 0x10});
    mfr = 0; fram.read_device_id(idConn, &mfr, &dens, &prod);
    check_true(mfr == 0x00A, "read_device_id_default");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
