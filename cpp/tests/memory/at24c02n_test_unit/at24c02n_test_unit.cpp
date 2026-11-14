#include <stdio.h>
#include <string.h>
#include "I2CConnectionMock.h"
#include "AT24C02N.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Number of logged writes carrying data (address bytes + at least one data byte).
static size_t data_frames(const I2CConnectionMock& c, size_t from) {
    size_t n = 0;
    for (size_t i = from; i < c.writes().size(); i++) if (c.writes()[i].size() > 2) n++;
    return n;
}

int main() {
    I2CConnectionMock connection;
        AT24C02NFull ee(connection);
    check_true(connection.writes().empty(), "init_no_traffic");
    check_true(ee.size() == 256, "size");
    check_true(ee.page_size() == 8, "page_size");

    connection.setRegister(0x0034, {0x42});
    check_true(ee.read_byte(0x0034) == 0x42, "read_byte");
    check_true(connection.writes().back().size() == 2 &&
               connection.writes().back()[0] == 0x12 && connection.writes().back()[1] == 0x34,
               "read_byte_address_bytes");
    check_true(ee.read_byte(0x00FF) == 0, "read_byte_high_address");

    size_t before = connection.writes().size();
    check_true(ee.write_byte(0x0010, 0x99), "write_byte_ok");
    check_true(connection.registers().at(0x0010) == 0x99, "write_byte");
    const auto& w = connection.writes()[before];
    check_true(w.size() == 3 && w[0] == 0x00 && w[1] == 0x10 && w[2] == 0x99, "write_byte_frame");
    const auto& poll = connection.writes()[before + 1];
    check_true(poll.size() == 2 && poll[0] == 0 && poll[1] == 0, "write_byte_ack_poll");

    AT24C02NMinimal minimal(connection);
    check_true(minimal.read_byte(0x0034) == 0x42, "minimal_class");

    // ACK polling: two NACKs, then the chip answers.
    connection.failNextWrites(2);
    before = connection.writes().size();
    check_true(ee.wait_ready(50), "wait_ready_after_nacks");
    check_true(connection.writes().size() - before == 3, "wait_ready_polled_three_times");
    connection.failNextWrites(1000);
    check_true(!ee.wait_ready(3), "wait_ready_timeout");
    connection.failNextWrites(0);
    connection.failNextWrites(1000, 1);
    check_true(!ee.write_byte(0x0021, 2), "write_byte_timeout_false");
    connection.failNextWrites(0);

    // Page splitting: 70 bytes from 0x0070 (page boundary at 0x0080, max_chunk 30).
    check_true(ee.max_chunk() == 30, "max_chunk_default");
    uint8_t data[70];
    for (int i = 0; i < 70; i++) data[i] = (uint8_t)i;
    before = connection.writes().size();
    check_true(ee.write(0x0070, data, 70), "write_ok");
    std::vector<std::vector<uint8_t>> frames;
    for (size_t i = before; i < connection.writes().size(); i++)
        if (connection.writes()[i].size() > 2) frames.push_back(connection.writes()[i]);
    check_true(frames.size() == 4, "write_chunk_count");
    check_true(frames[0].size() == 18 && frames[0][0] == 0x00 && frames[0][1] == 0x70 && frames[0][17] == 15,
               "write_first_chunk_stops_at_page");
    check_true(frames[1].size() == 32 && frames[1][0] == 0x00 && frames[1][1] == 0x80 && frames[1][2] == 16,
               "write_second_chunk_page_start");
    check_true(frames[2].size() == 4 && frames[2][1] == 0x9E && frames[2][3] == 47, "write_third_chunk_stops_at_page");
    check_true(frames[3].size() == 24 && frames[3][1] == 0xA0 && frames[3][23] == 69, "write_fourth_chunk");
    check_true(connection.writes().size() - before == 8, "write_ack_polled_per_chunk");
    uint8_t back[70];
    check_true(ee.read(0x0070, back, 70), "read_ok");
    check_true(memcmp(back, data, 70) == 0, "read_roundtrip");

    ee.set_max_chunk(8);
    before = connection.writes().size();
    uint8_t zeros[20] = {0};
    ee.write(0x00E0, zeros, 20);
    check_true(data_frames(connection, before) == 3, "custom_max_chunk");
    ee.set_max_chunk(100);
    check_true(ee.max_chunk() == 32, "max_chunk_clamped");
    ee.set_max_chunk(30);

    // write_page.
    uint8_t page[8];
    for (int i = 0; i < 8; i++) page[i] = (uint8_t)i;
    check_true(ee.write_page(0x00C0, page, 8), "write_page_full");
    uint8_t pb[8];
    ee.read(0x00C0, pb, 8);
    check_true(memcmp(pb, page, 8) == 0, "write_page_roundtrip");
    check_true(!ee.write_page(0x00CC, page, 8), "write_page_cross_rejected");
    check_true(!ee.write_page(0x00D0, page, 9), "write_page_too_long");

    // Fill.
    check_true(ee.fill(0x0040, 100, 0xAB), "fill_ok");
    bool all = true;
    for (int i = 0; i < 100; i++) all = all && connection.registers().at(0x0040 + i) == 0xAB;
    check_true(all, "fill");
    check_true(connection.registers().find(0x0040 + 100) == connection.registers().end(), "fill_end");
    ee.fill(0x0040, 4);
    check_true(connection.registers().at(0x0040) == 0 && connection.registers().at(0x0043) == 0, "fill_default_zero");

    // Range rules.
    uint8_t two[2] = {1, 2};
    check_true(ee.write(0x00FE, two, 2), "write_last_bytes");
    uint8_t rb[2] = {0, 0};
    check_true(ee.read(0x00FE, rb, 2) && rb[0] == 1 && rb[1] == 2, "read_last_bytes");
    check_true(!ee.write(0x00FF, two, 2), "write_wrap_rejected");
    uint8_t big[0x20];
    check_true(!ee.read(0x00F0, big, 0x20), "read_wrap_rejected");
    check_true(!ee.fill(0x00FF, 2), "fill_wrap_rejected");
    check_true(ee.read(0x0000, big, 0), "read_zero_length");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
