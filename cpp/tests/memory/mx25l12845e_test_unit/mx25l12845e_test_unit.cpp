#include <stdio.h>
#include <string.h>
#include <vector>
#include "Connection.h"
#include "MX25L12845E.h"
#include "RamBlockDevice.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Behavioural model of an MX25L12845E: 16 MiB array that can only be programmed 1 -> 0, 256-byte page
// wrap on PP, 4 KiB / 32 KiB / 64 KiB / chip erase, WEL/WIP handling (WIP reads high for a few RDSR polls
// after every program/erase), BP block protect with P_FAIL/E_FAIL, secured OTP with the factory ESN,
// deep power-down, RES/REMS/RDID/SFDP. Every command frame is recorded.
class FlashModel : public Connection {
public:
    std::vector<uint8_t> mem = std::vector<uint8_t>(16u * 1024 * 1024, 0xFF);
    uint8_t otp[512];
    bool inOtp = false, wel = false, dp = false, otpLocked = false;
    uint8_t sr = 0, scur = 0x01;      // scur bit0 = factory-locked OTP
    uint8_t id[3] = {0xC2, 0x20, 0x18};
    int busyPolls = 0, busyAfterOp = 2;
    bool stuckBusy = false;
    std::vector<uint8_t> cmds;        // opcode of every frame
    int pp = 0, se = 0, be32 = 0, be64 = 0, ce = 0;

    FlashModel() {
        memset(otp, 0xFF, sizeof(otp));
        for (int i = 0; i < 16; i++) otp[i] = static_cast<uint8_t>(0xA0 + i);   // ESN
    }

protected:
    uint32_t addr(const uint8_t* d) const { return (static_cast<uint32_t>(d[1]) << 16) | (d[2] << 8) | d[3]; }
    uint32_t protectedFrom() const {      // first protected byte address (top of array), 16 MiB if none
        uint8_t bp = (sr >> 2) & 0xF;
        if (bp == 0) return 16u * 1024 * 1024;
        if (bp >= 8) return 0;
        return 16u * 1024 * 1024 - (65536u << bp);   // 2^bp blocks of 64 KiB
    }
    bool isProtected(uint32_t a, uint32_t len) const { return a + len > protectedFrom(); }
    void startBusy() { busyPolls = busyAfterOp; wel = false; }

    void _write(const uint8_t* d, size_t n) override { frame(d, n, nullptr, 0); }
    void _read(uint8_t* buf, size_t n) override { memset(buf, 0, n); }
    void _write_read(const uint8_t* d, size_t dn, uint8_t* buf, size_t bn) override { frame(d, dn, buf, bn); }

    void frame(const uint8_t* d, size_t n, uint8_t* out, size_t on) {
        uint8_t op = d[0];
        cmds.push_back(op);
        if (out) memset(out, 0, on);
        if (dp && op != 0xAB) return;                     // deep power-down: only RDP/RES
        bool busy = stuckBusy || busyPolls > 0;
        if (busy && op != 0x05 && op != 0x2B && op != 0x9F && op != 0x30) return;
        switch (op) {
            case 0x06: wel = true; break;
            case 0x04: wel = false; break;
            case 0x9F: for (size_t i = 0; i < on && i < 3; i++) out[i] = id[i]; break;
            case 0x05:
                out[0] = static_cast<uint8_t>((sr & 0xFC) | (wel ? 2 : 0) | (busy ? 1 : 0));
                if (busyPolls > 0) busyPolls--;
                break;
            case 0x01: if (wel && n >= 2) { sr = d[1] & 0xFC; startBusy(); } break;
            case 0x03: case 0x0B: case 0x5A: {
                uint32_t a = addr(d);
                for (size_t i = 0; i < on; i++) {
                    if (op == 0x5A) out[i] = static_cast<uint8_t>(a + i);
                    else if (inOtp) out[i] = otp[(a + i) & 0x1FF];
                    else out[i] = mem[(a + i) & 0xFFFFFF];
                }
                break; }
            case 0x02: {
                if (!wel) break;
                uint32_t a = addr(d);
                size_t len = n - 4;
                pp++;
                if (inOtp) {
                    if (otpLocked) { scur |= 0x20; startBusy(); break; }
                    for (size_t i = 0; i < len; i++) otp[(a + i) & 0x1FF] &= d[4 + i];
                } else if (isProtected(a & ~255u, 256)) {
                    scur |= 0x20;                           // P_FAIL
                } else {
                    uint32_t page = a & ~255u;
                    for (size_t i = 0; i < len; i++) { uint32_t p = page + ((a + i) & 255); mem[p] &= d[4 + i]; }   // wraps in page
                }
                startBusy();
                break; }
            case 0x20: case 0x52: case 0xD8: {
                if (!wel) break;
                uint32_t size = op == 0x20 ? 4096 : op == 0x52 ? 32768 : 65536;
                uint32_t a = addr(d) & ~(size - 1);
                if (op == 0x20) se++; else if (op == 0x52) be32++; else be64++;
                if (isProtected(a, size)) scur |= 0x40;     // E_FAIL
                else memset(&mem[a], 0xFF, size);
                startBusy();
                break; }
            case 0xC7: case 0x60:
                if (!wel) break;
                ce++;
                if (((sr >> 2) & 0xF) != 0) scur |= 0x40;
                else std::fill(mem.begin(), mem.end(), 0xFF);
                startBusy();
                break;
            case 0xB9: dp = true; break;
            case 0xAB: dp = false; if (out && on) out[0] = 0x17; break;
            case 0x90: if (out && on >= 2) { out[0] = 0xC2; out[1] = 0x17; } break;
            case 0xB1: inOtp = true; break;
            case 0xC1: inOtp = false; break;
            case 0x2B: out[0] = scur; break;
            case 0x2F: if (wel) { scur |= 0x02; otpLocked = true; startBusy(); } break;
            case 0x30: scur &= ~0x60; break;
            default: break;
        }
    }
};

static int count(const FlashModel& m, uint8_t op) {
    int c = 0;
    for (uint8_t o : m.cmds) if (o == op) c++;
    return c;
}

static void test_init() {
    FlashModel m;
    MX25L12845EMinimal f(m);
    check_true(f.status() == BlockError::Ok, "init ok on C2 20 18");
    uint8_t id[3];
    f.jedec_id(id);
    check_true(id[0] == 0xC2 && id[1] == 0x20 && id[2] == 0x18, "jedec_id");
    check_true(f.size() == 16u * 1024 * 1024, "size 16 MiB");

    FlashModel wrong; wrong.id[2] = 0x17;
    MX25L12845EMinimal bad(wrong);
    check_true(bad.status() == BlockError::NotReady, "wrong ID -> NotReady");
    uint8_t b;
    check_true(bad.read_bytes(0, &b, 1) == BlockError::NotReady, "operations refused after failed init");

    FlashModel stuck; stuck.stuckBusy = true;
    MX25L12845EMinimal s(stuck);
    check_true(s.status() == BlockError::Timeout, "chip stuck busy -> Timeout at init");

    FlashModel asleep; asleep.dp = true;
    MX25L12845EMinimal w(asleep);
    check_true(w.status() == BlockError::Ok, "init releases deep power-down (RDP)");
}

static void test_minimal_write() {
    FlashModel m;
    MX25L12845EMinimal f(m);
    uint8_t pattern[600], back[600];
    for (int i = 0; i < 600; i++) pattern[i] = static_cast<uint8_t>(i * 7 + 3);

    check_true(f.write_bytes(0x1F0, pattern, 600) == BlockError::Ok, "write across a page boundary into blank flash");
    check_true(f.read_bytes(0x1F0, back, 600) == BlockError::Ok && !memcmp(pattern, back, 600), "read back");
    check_true(m.se == 0, "blank flash: no erase needed");
    check_true(m.mem[0x1EF] == 0xFF && m.mem[0x1F0 + 600] == 0xFF, "neighbouring bytes untouched");

    int ppBefore = m.pp;
    f.write_bytes(0x1F0, pattern, 600);
    check_true(m.pp == ppBefore && m.se == 0, "identical rewrite programs and erases nothing");

    uint8_t more[600];
    for (int i = 0; i < 600; i++) more[i] = pattern[i] & static_cast<uint8_t>(0xF0);   // only 1 -> 0
    f.write_bytes(0x1F0, more, 600);
    f.read_bytes(0x1F0, back, 600);
    check_true(!memcmp(more, back, 600) && m.se == 0, "1->0 only rewrite needs no erase");

    uint8_t ones[10]; memset(ones, 0xFF, sizeof(ones));
    check_true(f.write_bytes(0x1F0, ones, 10) == BlockError::Ok && m.se == 1, "0->1 needs a sector erase");
    f.read_bytes(0x1F0, back, 10);
    check_true(!memcmp(ones, back, 10), "erased bytes now 0xFF");
    f.read_bytes(0x200, back, 20);
    check_true(!memcmp(back, more + 16, 20), "rest of the sector survived the erase (read-modify-write)");

    uint8_t big[9000];
    for (size_t i = 0; i < sizeof(big); i++) big[i] = static_cast<uint8_t>(i ^ (i >> 8));
    check_true(f.write_bytes(0x2FF0, big, sizeof(big)) == BlockError::Ok, "multi-sector write");
    static uint8_t bigback[9000];
    f.read_bytes(0x2FF0, bigback, sizeof(big));
    check_true(!memcmp(big, bigback, sizeof(big)), "multi-sector read back");
    check_true(f.write_bytes(MX25L12845EMinimal::SIZE - 2, big, 5) == BlockError::OutOfRange, "write past the end");
    check_true(f.erase_sector(0x2000) == BlockError::Ok && m.mem[0x2000] == 0xFF, "erase_sector");
    check_true(count(m, 0x06) > 0 && m.cmds.size() > 10, "WREN issued before changes");
}

static void test_protection() {
    FlashModel m;
    MX25L12845EFull f(m);
    uint8_t bp = 9;
    check_true(f.protection(&bp) == BlockError::Ok && bp == 0, "BP = 0 at power-up");
    check_true(f.set_protection(8) == BlockError::Ok, "set_protection(8): everything");
    f.protection(&bp);
    check_true(bp == 8, "BP reads back");
    uint8_t d = 0x00;
    check_true(f.page_program(0x100, &d, 1) == BlockError::WriteProtected, "program into protected area -> WriteProtected");
    check_true(m.mem[0x100] == 0xFF, "protected data unchanged");
    uint8_t scur = 0xFF;
    f.read_security(&scur);
    check_true((scur & 0x60) == 0, "fail flags cleared by the driver (CLSR)");
    check_true(f.erase_sector(0) == BlockError::WriteProtected, "erase of protected sector -> WriteProtected");
    check_true(f.chip_erase() == BlockError::Io || f.chip_erase() == BlockError::WriteProtected, "chip erase refused while protected");
    check_true(f.info().read_only, "info().read_only for BP >= 8");
    check_true(f.set_protection(16) == BlockError::OutOfRange, "BP > 15 rejected");
    f.set_protection(0);
    check_true(f.page_program(0x100, &d, 1) == BlockError::Ok && m.mem[0x100] == 0x00, "program after unprotect");
}

static void test_raw_full() {
    FlashModel m;
    MX25L12845EFull f(m);
    uint8_t data[300];
    for (int i = 0; i < 300; i++) data[i] = static_cast<uint8_t>(i);
    check_true(f.page_program(0x1000, data, 256) == BlockError::Ok, "page_program 256");
    check_true(f.page_program(0x10C0, data, 100) == BlockError::OutOfRange, "page_program crossing a page is refused");
    check_true(f.page_program(0, data, 257) == BlockError::OutOfRange, "page_program > 256 refused");
    uint8_t a[300], b[300];
    f.read_bytes(0x1000, a, 256);
    f.fast_read(0x1000, b, 256);
    check_true(!memcmp(a, data, 256) && !memcmp(b, data, 256), "READ and FAST_READ agree");
    check_true(f.erase_block_32k(0x8000) == BlockError::Ok && m.be32 == 1, "32 KiB block erase");
    check_true(f.erase_block_64k(0x10000) == BlockError::Ok && m.be64 == 1, "64 KiB block erase");
    check_true(f.chip_erase() == BlockError::Ok && m.ce == 1 && m.mem[0x1000] == 0xFF, "chip erase");
    uint8_t id = 0, man = 0, dev = 0;
    f.electronic_id(&id);
    f.device_ids(&man, &dev);
    check_true(id == 0x17 && man == 0xC2 && dev == 0x17, "RES / REMS ids");
    check_true(f.deep_power_down() == BlockError::Ok && m.dp, "deep power-down");
    check_true(f.release_deep_power_down() == BlockError::Ok && !m.dp, "release deep power-down");
    uint8_t sfdp[8];
    check_true(f.read_sfdp(0, sfdp, 8) == BlockError::Ok && sfdp[1] == 1, "read_sfdp");
    check_true(!f.is_busy(), "idle after operations");
    check_true(f.write_status(0x04) == BlockError::Ok && m.sr == 0x04, "write_status");
    f.write_status(0);
}

static void test_otp() {
    FlashModel m;
    MX25L12845EFull f(m);
    uint8_t esn[16];
    check_true(f.serial_number(esn) == BlockError::Ok && esn[0] == 0xA0 && esn[15] == 0xAF, "factory serial number");
    check_true(!m.inOtp, "OTP mode exited");
    uint8_t user[4] = {1, 2, 3, 4}, back[4];
    check_true(f.program_otp(16, user, 4) == BlockError::Ok, "program_otp (customer area)");
    check_true(f.read_otp(16, back, 4) == BlockError::Ok && !memcmp(user, back, 4), "read_otp");
    check_true(f.program_otp(8, user, 4) == BlockError::OutOfRange, "ESN area refused");
    check_true(f.program_otp(510, user, 4) == BlockError::OutOfRange, "past the end of the OTP refused");
    check_true(f.lock_otp() == BlockError::Ok && m.otpLocked, "lock_otp (WRSCUR)");
}

static void test_blockdevice() {
    FlashModel m;
    MX25L12845EFull f(m);
    check_true(f.block_size() == 512 && f.block_count() == 32768 && f.erase_size() == 4096 && f.needs_erase(), "geometry");
    BlockInfo i = f.info();
    check_true(!strcmp(i.name, "MX25L12845E") && i.needs_erase && i.erase_size == 4096, "info()");

    uint8_t w[1024], r[1024];
    for (int k = 0; k < 1024; k++) w[k] = static_cast<uint8_t>(k * 5 + 1);
    check_true(f.write(8, w, 2) == BlockError::Ok, "write blank blocks (auto_erase off)");
    check_true(f.read(8, r, 2) == BlockError::Ok && !memcmp(w, r, 1024), "read blocks");
    check_true(f.write(8, w, 2) == BlockError::Ok, "identical rewrite is fine");
    uint8_t ones[512]; memset(ones, 0xFF, sizeof(ones));
    check_true(f.write(8, ones, 1) == BlockError::NotErased, "needing 0->1 without auto_erase -> NotErased");
    check_true(m.se == 0, "NotErased did not erase anything");
    check_true(f.erase(8, 8) == BlockError::Ok && m.mem[8 * 512] == 0xFF, "erase aligned sector");
    check_true(f.erase(9, 8) == BlockError::OutOfRange, "unaligned erase -> OutOfRange");
    check_true(f.erase(32768 - 8, 16) == BlockError::OutOfRange, "erase past the end");
    check_true(f.read(32768, r, 1) == BlockError::OutOfRange, "read past the end");
    check_true(f.sync() == BlockError::Ok, "sync");

    int se0 = m.se, be64_0 = m.be64, be32_0 = m.be32;
    f.erase(0, 128 + 64 + 8);   // 64 KiB + 32 KiB + 4 KiB, aligned
    check_true(m.be64 - be64_0 == 1 && m.be32 - be32_0 == 1 && m.se - se0 == 1, "erase picks 64K, then 32K, then 4K");

    MX25L12845EOptions o; o.auto_erase = true;
    FlashModel m2;
    MX25L12845EFull g(m2, o);
    g.write(0, w, 2);
    check_true(g.write(0, ones, 1) == BlockError::Ok && m2.se == 1, "auto_erase handles 0->1");
    uint8_t back[512];
    g.read(0, back, 1);
    check_true(!memcmp(back, ones, 512), "auto_erase result");
    g.read(1, back, 1);
    check_true(!memcmp(back, w + 512, 512), "neighbouring block kept");

    RamBlockDevice ram(512, 64, 8);
    BlockDevice* devs[2] = { &g, &ram };
    for (int d = 0; d < 2; d++) {
        uint8_t x[512], y[512];
        memset(x, 0x5A, sizeof(x));
        devs[d]->erase(64, 8);
        char label[48];
        snprintf(label, sizeof(label), "contract %d: erase+write+read", d);
        check_true(devs[d]->write(d ? 0 : 64, x, 1) == BlockError::Ok && devs[d]->read(d ? 0 : 64, y, 1) == BlockError::Ok && !memcmp(x, y, 512), label);
        snprintf(label, sizeof(label), "contract %d: out of range", d);
        check_true(devs[d]->read(devs[d]->block_count(), y, 1) == BlockError::OutOfRange, label);
    }
}

static void test_timeout() {
    FlashModel m;
    MX25L12845EFull f(m);
    m.stuckBusy = true;
    check_true(f.wait_ready(5) == BlockError::Timeout, "wait_ready times out");
    check_true(f.is_busy(), "is_busy true while stuck");
    m.stuckBusy = false;
}

int main() {
    test_init();
    test_minimal_write();
    test_protection();
    test_raw_full();
    test_otp();
    test_blockdevice();
    test_timeout();
    printf("\n%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
