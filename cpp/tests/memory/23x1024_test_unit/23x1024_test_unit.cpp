#include <stdio.h>
#include <string.h>
#include <vector>
#include "Connection.h"
#include "23X1024.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Behavioural model of a 23X1024: parses the instruction frames the driver
// sends, models the 131072-byte array, the MODE register and the
// Byte/Page/Sequential address-increment rules, and records each frame.
class SramModel : public Connection {
public:
    std::vector<uint8_t> mem = std::vector<uint8_t>(131072, 0);
    std::vector<std::vector<uint8_t>> frames;
    uint8_t mode = 0x40;
    uint32_t pointer = 0;

protected:
    void _write(const uint8_t* data, size_t len) override {
        frames.emplace_back(data, data + len);
        if (len == 0) return;
        if (data[0] == 0x01 && len == 2) { mode = data[1]; return; }
        if (data[0] == 0xFF) { return; }
        if (data[0] == 0x02 && len >= 4) {
            uint32_t addr = ((uint32_t)data[1] << 16) | ((uint32_t)data[2] << 8) | data[3];
            for (size_t i = 4; i < len; i++) {
                mem[addr] = data[i];
                addr = next(addr);
                if ((mode & 0xC0) == 0x00) break;  // Byte mode: one data byte only
            }
        }
    }
    void _read(uint8_t* buf, size_t len) override { memset(buf, 0, len); }
    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        frames.emplace_back(data, data + data_len);
        if (data[0] == 0x05) { buf[0] = mode; return; }
        if (data[0] == 0x03 && data_len == 4) {
            uint32_t addr = ((uint32_t)data[1] << 16) | ((uint32_t)data[2] << 8) | data[3];
            for (size_t i = 0; i < buf_len; i++) {
                buf[i] = mem[addr];
                addr = next(addr);
                if ((mode & 0xC0) == 0x00) { for (size_t j = i + 1; j < buf_len; j++) buf[j] = 0; break; }
            }
        }
    }
private:
    uint32_t next(uint32_t addr) const {
        if ((mode & 0xC0) == 0x80) return (addr & ~31u) | ((addr + 1) & 31u);  // Page: wrap in page
        return (addr + 1) & 0x1FFFF;                                          // Sequential: wrap at end
    }
};

int main() {
    // --- Construction: WRMR 0x40 then RDMR ---
    SramModel conn;
    SRAM23X1024Minimal chip(conn);
    check_true(conn.frames.size() == 2, "init_two_frames");
    check_true(conn.frames[0] == std::vector<uint8_t>({0x01, 0x40}), "init_wrmr_sequential");
    check_true(conn.frames[1] == std::vector<uint8_t>({0x05}), "init_rdmr");

    // --- Minimal byte access: frame layout + round trip ---
    chip.write_byte(0x1ABCD, 0x5A);
    check_true(conn.frames.back() == std::vector<uint8_t>({0x02, 0x01, 0xAB, 0xCD, 0x5A}), "write_byte_frame");
    check_true(conn.mem[0x1ABCD] == 0x5A, "write_byte_stored");
    check_true(chip.read_byte(0x1ABCD) == 0x5A, "read_byte_roundtrip");
    check_true(conn.frames.back() == std::vector<uint8_t>({0x03, 0x01, 0xAB, 0xCD}), "read_byte_frame");
    chip.write_byte(0x1FFFF, 0x77);
    check_true(chip.read_byte(0x1FFFF) == 0x77, "last_address");

    // --- Full: block access ---
    SramModel fc;
    SRAM23X1024Full full(fc);
    check_true(full.size() == 131072, "size");
    uint8_t pattern[600];
    for (int i = 0; i < 600; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    full.write(0x00100, pattern, 600);
    check_true(memcmp(&fc.mem[0x100], pattern, 600) == 0, "write_block");
    uint8_t out[600] = {0};
    full.read(0x00100, out, 600);
    check_true(memcmp(out, pattern, 600) == 0, "read_block");
    // 600 bytes with max_chunk 256 => 3 write frames (256+256+88)
    size_t writes = 0;
    for (auto& f : fc.frames) if (f[0] == 0x02) writes++;
    check_true(writes == 3, "write_chunked_256");

    // --- max_chunk ---
    full.set_max_chunk(100);
    check_true(full.max_chunk() == 100, "set_max_chunk");
    fc.frames.clear();
    full.write(0, pattern, 250);
    check_true(fc.frames.size() == 3 && fc.frames[0].size() == 104 && fc.frames[2].size() == 54, "write_chunked_custom");
    full.set_max_chunk(256);

    // --- fill ---
    full.fill(0x2000, 100, 0xA5);
    bool ok = true;
    for (int i = 0; i < 100; i++) ok = ok && fc.mem[0x2000 + i] == 0xA5;
    check_true(ok && fc.mem[0x2000 + 100] == 0 && fc.mem[0x1FFF] == 0, "fill_range_only");
    full.fill(0x3000, 5);
    check_true(fc.mem[0x3000] == 0 && fc.mem[0x3004] == 0, "fill_default_zero");

    // --- Page mode: transfers split at 32-byte boundaries, linear semantics preserved ---
    full.set_mode(SRAM23X1024Full::MODE_PAGE);
    check_true(fc.mode == 0x80 && full.get_mode() == SRAM23X1024Full::MODE_PAGE, "set_mode_page");
    fc.frames.clear();
    uint8_t data64[64];
    for (int i = 0; i < 64; i++) data64[i] = (uint8_t)(0xC0 + i);
    full.write(0x4010, data64, 64);  // 0x4010..0x404F crosses boundaries at 32 and 64
    check_true(fc.frames.size() == 3, "page_write_split_frames");
    check_true(memcmp(&fc.mem[0x4010], data64, 64) == 0 && fc.mem[0x4050] == 0, "page_write_linear");
    uint8_t back[64];
    full.read(0x4010, back, 64);
    check_true(memcmp(back, data64, 64) == 0, "page_read_linear");

    // --- Byte mode: one byte per transaction ---
    full.set_mode(SRAM23X1024Full::MODE_BYTE);
    fc.frames.clear();
    full.write(0x500, data64, 4);
    check_true(fc.frames.size() == 4, "byte_mode_frame_per_byte");
    full.read(0x500, back, 4);
    check_true(memcmp(back, data64, 4) == 0, "byte_mode_read");
    full.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);
    check_true(full.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "set_mode_sequential");

    // --- get_mode masks reserved bits ---
    fc.mode = 0x4F;
    check_true(full.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "get_mode_masks_reserved");
    fc.mode = 0x40;

    // --- reset_io sends 0xFF and resyncs the cached mode ---
    fc.frames.clear();
    full.reset_io();
    check_true(fc.frames[0] == std::vector<uint8_t>({0xFF}), "reset_io_frame");

    // --- Range edge: last bytes of the array are reachable ---
    full.write(0x1FFFE, data64, 2);
    full.read(0x1FFFE, back, 2);
    check_true(back[0] == data64[0] && back[1] == data64[1], "block_at_array_end");

    printf("%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
