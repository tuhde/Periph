#include <stdio.h>
#include <string.h>
#include <deque>
#include <vector>
#include "Connection.h"
#include "SPIExtensions.h"
#include "SD_SPI.h"
#include "RamBlockDevice.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static uint8_t crc7(const uint8_t* d, size_t n) {
    uint8_t c = 0;
    for (size_t i = 0; i < n; i++) {
        uint8_t b = d[i];
        for (int k = 0; k < 8; k++) { c <<= 1; if ((b ^ c) & 0x80) c ^= 0x09; b <<= 1; }
    }
    return c & 0x7F;
}

static uint16_t crc16(const uint8_t* d, size_t n) {
    uint16_t c = 0;
    for (size_t i = 0; i < n; i++) {
        c ^= (uint16_t)d[i] << 8;
        for (int k = 0; k < 8; k++) c = (c & 0x8000) ? (uint16_t)((c << 1) ^ 0x1021) : (uint16_t)(c << 1);
    }
    return c;
}

// Behavioural model of an SD card in SPI mode, as seen through the byte-level
// Connection + SPIExtensions interface. Models the init sequence (needs 80
// idle clocks first), command CRC7 checking, R1/R3/R7 responses, CSD/CID,
// single/multi block read and write with tokens, busy and CRC16.
class SdModel : public Connection, public SPIExtensions {
public:
    bool v2 = true, sdhc = true;
    uint32_t csizeV2 = 7;                  // (7+1)*512 KiB = 4 MiB = 8192 blocks
    int acmd41Calls = 0, acmd41Needed = 3;
    bool gotIdleClocks = false, selected = false, inIdle = false, crcOn = false;
    bool corruptReadCrc = false;
    uint32_t speed = 0;
    int speedChanges = 0;
    std::vector<uint8_t> mem = std::vector<uint8_t>(8192 * 512, 0);
    std::vector<uint8_t> cmdLog;           // command numbers seen
    int csTogglesInsideCommand = 0;

    Connection& connection() override { return *this; }
    void idle_clocks(uint32_t n) override { if (!selected && n >= 80) gotIdleClocks = true; }
    bool set_speed(uint32_t hz) override { speed = hz; speedChanges++; return true; }
    void select() override { selected = true; }
    void deselect() override { selected = false; if (state == CMD && cmdCount) csTogglesInsideCommand++; }

protected:
    void _write(const uint8_t* d, size_t n) override { for (size_t i = 0; i < n; i++) feed(d[i]); }
    void _read(uint8_t* b, size_t n) override { for (size_t i = 0; i < n; i++) b[i] = feed(0xFF); }
    void _write_read(const uint8_t* d, size_t dn, uint8_t* b, size_t n) override {
        for (size_t i = 0; i < dn; i++) feed(d[i]);
        for (size_t i = 0; i < n; i++) b[i] = feed(0xFF);
    }

private:
    enum State { CMD, WTOKEN, WDATA } state = CMD;
    uint8_t cmdBuf[6]; int cmdCount = 0;
    std::deque<uint8_t> out;
    bool appNext = false;
    bool streaming = false; uint32_t streamBlock = 0;
    uint32_t wBlock = 0; bool multiWrite = false;
    std::vector<uint8_t> wData; int wNeed = 0;

    uint32_t blocks() const { return sdhc ? (csizeV2 + 1) * 1024 : 4096; }

    void pushBlock(const uint8_t* data, size_t n) {
        out.push_back(0xFE);
        for (size_t i = 0; i < n; i++) out.push_back(data[i]);
        uint16_t c = crc16(data, n);
        if (corruptReadCrc) c ^= 0x0001;
        out.push_back(c >> 8); out.push_back(c & 0xFF);
    }

    uint32_t blockOf(uint32_t arg) const { return sdhc ? arg : arg / 512; }

    void doCommand() {
        bool wasApp = appNext; appNext = false;
        uint8_t cmd = cmdBuf[0] & 0x3F;
        uint32_t arg = ((uint32_t)cmdBuf[1] << 24) | ((uint32_t)cmdBuf[2] << 16) | ((uint32_t)cmdBuf[3] << 8) | cmdBuf[4];
        cmdLog.push_back(wasApp ? (uint8_t)(0x80 | cmd) : cmd);
        if ((cmdBuf[5] >> 1) != crc7(cmdBuf, 5)) { out.push_back(0xFF); out.push_back(0x09); return; }
        out.push_back(0xFF);  // Ncr gap
        uint8_t idleBit = inIdle ? 0x01 : 0x00;
        if (wasApp) {
            if (cmd == 41) {
                if (++acmd41Calls >= acmd41Needed) { inIdle = false; out.push_back(0x00); }
                else out.push_back(0x01);
            } else out.push_back(idleBit);  // ACMD23 etc.
            return;
        }
        switch (cmd) {
            case 0:
                if (!gotIdleClocks) { out.clear(); return; }  // not in SPI mode -> no response
                inIdle = true; out.push_back(0x01); break;
            case 8:
                if (!v2) { out.push_back(0x05); break; }
                out.push_back(0x01); out.push_back(0); out.push_back(0); out.push_back(0x01); out.push_back(0xAA); break;
            case 9: {
                uint8_t csd[16] = {0};
                if (sdhc) { csd[0] = 0x40; csd[7] = (csizeV2 >> 16) & 0x3F; csd[8] = (csizeV2 >> 8) & 0xFF; csd[9] = csizeV2 & 0xFF; }
                else { csd[0] = 0x00; csd[5] = 9; csd[6] = 0x03; csd[7] = 0xFF; csd[8] = 0xC0; csd[9] = 0x03; csd[10] = 0x80; }
                out.push_back(0x00); out.push_back(0xFF); pushBlock(csd, 16); break; }
            case 10: {
                uint8_t cid[16] = {0x03, 'S', 'D', 'T', 'E', 'S', 'T', '1', 0x10, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x23, 0x01};
                out.push_back(0x00); out.push_back(0xFF); pushBlock(cid, 16); break; }
            case 12: streaming = false; out.clear(); out.push_back(0xFF); out.push_back(0x00); out.push_back(0x00); out.push_back(0x00); out.push_back(0xFF);
                     // R1 comes after the stuff byte consumed by the driver
                     out.clear(); out.push_back(0xFF); out.push_back(0x00); out.push_back(0x00); out.push_back(0x00); out.push_back(0xFF); break;
            case 13: out.push_back(0x00); out.push_back(0x00); break;
            case 16: out.push_back(idleBit); break;
            case 17: out.push_back(0x00); out.push_back(0xFF); pushBlock(&mem[blockOf(arg) * 512], 512); break;
            case 18: out.push_back(0x00); streaming = true; streamBlock = blockOf(arg); break;
            case 24: case 25:
                out.push_back(0x00); state = WTOKEN; wBlock = blockOf(arg); multiWrite = (cmd == 25); break;
            case 32: case 33: out.push_back(0x00); break;
            case 38: out.push_back(0x00); out.push_back(0x00); out.push_back(0x00); break;
            case 55: appNext = true; out.push_back(idleBit); break;
            case 58: out.push_back(idleBit); out.push_back(sdhc ? 0xC0 : 0x80); out.push_back(0xFF); out.push_back(0x80); out.push_back(0x00); break;
            case 59: crcOn = arg & 1; out.push_back(idleBit); break;
            default: out.push_back(0x04); break;  // illegal command
        }
    }

    uint8_t feed(uint8_t in) {
        if (state == WTOKEN) {
            if (in == 0xFE || in == 0xFC) { state = WDATA; wData.clear(); wNeed = 514; }
            else if (in == 0xFD) { state = CMD; out.push_back(0xFF); out.push_back(0x00); out.push_back(0x00); out.push_back(0xFF); return 0xFF; }
        } else if (state == WDATA) {
            wData.push_back(in);
            if ((int)wData.size() == wNeed) {
                uint16_t got = (uint16_t)((wData[512] << 8) | wData[513]);
                bool ok = !crcOn || got == crc16(wData.data(), 512);
                if (ok) { memcpy(&mem[wBlock * 512], wData.data(), 512); wBlock++; }
                out.push_back(ok ? 0xE5 : 0xEB);
                out.push_back(0x00); out.push_back(0x00); out.push_back(0xFF);
                state = multiWrite ? WTOKEN : CMD;
                return 0xFF;  // the data response follows on the NEXT byte clock
            }
        } else {
            if (cmdCount == 0 && (in & 0xC0) != 0x40) {
                // not a command start: shift out pending bytes
            } else if (cmdCount > 0 || (in & 0xC0) == 0x40) {
                cmdBuf[cmdCount++] = in;
                if (cmdCount == 6) { cmdCount = 0; doCommand(); }
                return 0xFF;
            }
        }
        if (!out.empty()) { uint8_t b = out.front(); out.pop_front(); return b; }
        if (streaming && state == CMD) {
            pushBlock(&mem[streamBlock * 512], 512); streamBlock++;
            uint8_t b = out.front(); out.pop_front(); return b;
        }
        return 0xFF;
    }
};

static void test_init_v2_sdhc() {
    SdModel m;
    SDSPIMinimal sd(m);
    check_true(sd.status() == BlockError::Ok, "SDHC init succeeds");
    check_true(m.gotIdleClocks, "80 idle clocks sent before CMD0");
    check_true(sd.card_type() == SDSPIMinimal::CardType::SDHC, "SDHC detected via OCR CCS");
    check_true(sd.block_count() == 8192, "block count from CSD v2");
    check_true(m.speed == 12000000, "speed raised to 12 MHz after init");
    check_true(m.csTogglesInsideCommand == 0, "no CS release mid-command");
}

static void test_init_v1() {
    SdModel m; m.v2 = false; m.sdhc = false;
    SDSPIMinimal sd(m);
    check_true(sd.status() == BlockError::Ok, "SDv1 init succeeds");
    check_true(sd.card_type() == SDSPIMinimal::CardType::SDv1, "SDv1 detected");
    check_true(sd.block_count() == 2097152, "block count from CSD v1 (1 GiB)");
    bool sawCmd16 = false;
    for (uint8_t c : m.cmdLog) if (c == 16) sawCmd16 = true;
    check_true(sawCmd16, "CMD16 sent for byte-addressed card");
}

static void test_no_card() {
    class NoCard : public SdModel {} m;
    m.gotIdleClocks = false;
    struct Dead : public Connection, public SPIExtensions {
        Connection& connection() override { return *this; }
        void idle_clocks(uint32_t) override {}
        bool set_speed(uint32_t) override { return true; }
        void select() override {}
        void deselect() override {}
        void _write(const uint8_t*, size_t) override {}
        void _read(uint8_t* b, size_t n) override { memset(b, 0xFF, n); }
        void _write_read(const uint8_t*, size_t, uint8_t* b, size_t n) override { memset(b, 0xFF, n); }
    } dead;
    SDSPIMinimal sd(dead);
    check_true(sd.status() == BlockError::NotReady, "no card -> NotReady");
}

static void test_minimal_block_io() {
    SdModel m;
    SDSPIMinimal sd(m);
    uint8_t w[512], r[512];
    for (int i = 0; i < 512; i++) w[i] = (uint8_t)(i * 7 + 3);
    check_true(sd.write_block(5, w) == BlockError::Ok, "write_block ok");
    check_true(sd.read_block(5, r) == BlockError::Ok, "read_block ok");
    check_true(memcmp(w, r, 512) == 0, "block round-trip");
    check_true(sd.read_block(8192, r) == BlockError::OutOfRange, "read past end -> OutOfRange");
}

static void test_full_multi() {
    SdModel m;
    SDSPIFull sd(m);
    check_true(sd.status() == BlockError::Ok, "Full init ok");
    check_true(sd.block_size() == 512 && sd.erase_size() == 512 && !sd.needs_erase(), "geometry");
    BlockInfo i = sd.info();
    check_true(strcmp(i.name, "TEST1") == 0, "info().name from CID PNM");
    static uint8_t w[4 * 512], r[4 * 512];
    for (size_t k = 0; k < sizeof(w); k++) w[k] = (uint8_t)(k ^ (k >> 8));
    check_true(sd.write(100, w, 4) == BlockError::Ok, "multi-block write ok");
    check_true(sd.sync() == BlockError::Ok, "sync ok");
    check_true(sd.read(100, r, 4) == BlockError::Ok, "multi-block read ok");
    check_true(memcmp(w, r, sizeof(w)) == 0, "multi-block round-trip");
    check_true(sd.erase(100, 4) == BlockError::Ok, "erase ok");
    check_true(sd.write(8190, w, 4) == BlockError::OutOfRange, "multi write past end -> OutOfRange");
    uint32_t ocr = 0;
    check_true(sd.ocr(&ocr) == BlockError::Ok && (ocr & 0x40000000), "OCR CCS bit");
    check_true(sd.is_present(), "is_present");
}

static void test_crc_error() {
    SdModel m; m.corruptReadCrc = false;
    SDSPIFull sd(m);
    m.corruptReadCrc = true;
    uint8_t r[512];
    check_true(sd.read_block(1, r) == BlockError::Crc, "corrupt read CRC16 -> Crc");
    check_true(sd.set_crc(false) == BlockError::Ok, "set_crc(false)");
    check_true(sd.read_block(1, r) == BlockError::Ok, "CRC ignored when off");
}

static void test_blockdevice_contract() {
    SdModel m;
    SDSPIFull sd(m);
    RamBlockDevice ram(512, 64);
    BlockDevice* devs[2] = { &sd, &ram };
    const char* names[2] = { "SD", "RAM" };
    for (int d = 0; d < 2; d++) {
        uint8_t w[512], r[512];
        memset(w, 0xA0 + d, sizeof(w));
        char label[64];
        snprintf(label, sizeof(label), "%s contract: write+read", names[d]);
        check_true(devs[d]->write(3, w, 1) == BlockError::Ok && devs[d]->read(3, r, 1) == BlockError::Ok && !memcmp(w, r, 512), label);
        snprintf(label, sizeof(label), "%s contract: out of range", names[d]);
        check_true(devs[d]->read(devs[d]->block_count(), r, 1) == BlockError::OutOfRange, label);
    }
}

int main() {
    test_init_v2_sdhc();
    test_init_v1();
    test_no_card();
    test_minimal_block_io();
    test_full_multi();
    test_crc_error();
    test_blockdevice_contract();
    printf("\n%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
