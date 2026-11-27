#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <deque>
#include <vector>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Pn532.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> Bytes;

static const Bytes ACK = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00};
static const Bytes NACK = {0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00};
static const Bytes ERROR_FRAME = {0x00, 0x00, 0xFF, 0x01, 0xFF, 0x7F, 0x81, 0x00};

static Bytes frame(uint8_t tfi, const Bytes& data) {
    Bytes body;
    body.push_back(tfi);
    body.insert(body.end(), data.begin(), data.end());
    uint8_t sum = 0;
    for (uint8_t b : body) sum = (uint8_t)(sum + b);
    Bytes f = {0x00, 0x00, 0xFF, (uint8_t)body.size(), (uint8_t)(0x100 - body.size())};
    f.insert(f.end(), body.begin(), body.end());
    f.push_back((uint8_t)(0x100 - sum));
    f.push_back(0x00);
    return f;
}

struct Command { uint8_t cmd; Bytes params; };

// Simulates the PN532 I²C frame protocol: ACK, response, NACK resend, ACK abort, status byte.
class PN532Sim : public I2CConnectionMock {
public:
    std::deque<Bytes> queue;
    Bytes last;
    std::vector<Command> commands;
    std::vector<size_t> reads;
    bool card = true, noAck = false, noResponse = false, errorFrame = false, badDcs = false;
    uint8_t ic = 0x32, status = 0;
    Bytes uid = {0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
    Bytes listResponse;
    bool useListResponse = false;
    uint8_t regs[0x10000 >> 8] = {0};

    Bytes respond(uint8_t cmd, const Bytes& p) {
        switch (cmd) {
        case 0x02: return {ic, 0x01, 0x06, 0x07};
        case 0x04: return {0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01};
        case 0x4A: {
            if (useListResponse) return listResponse;
            if (!card) return {0x00};
            Bytes r = {1, 1, 0x00, 0x44, 0x00, (uint8_t)uid.size()};
            r.insert(r.end(), uid.begin(), uid.end());
            return r;
        }
        case 0x52: case 0x54: case 0x44: case 0x16: return {status};
        case 0x40: {
            if (status) return {status};
            if (p.size() > 1 && p[1] == 0x30) {
                Bytes r = {0x00};
                for (int i = 0; i < 16; i++) r.push_back((uint8_t)(p[2] + i));
                return r;
            }
            return {0x00};
        }
        case 0x42: {
            Bytes r = {0x00};
            r.insert(r.end(), p.rbegin(), p.rend());
            return r;
        }
        case 0x06: return {regs[p[1]]};
        case 0x08: regs[p[1]] = p[2]; return {};
        case 0x00: return p[0] == 0 ? p : Bytes{0x00};
        }
        return {};
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (!ok()) return;
        Bytes d(data, data + len);
        if (d == ACK) { queue.clear(); return; }
        if (d == NACK) { if (!last.empty()) queue.push_back(last); return; }
        uint8_t ln = d[3];
        Command c{d[6], Bytes(d.begin() + 7, d.begin() + 5 + ln)};
        commands.push_back(c);
        if (noAck) return;
        queue.clear();
        queue.push_back(ACK);
        if (noResponse) return;
        if (errorFrame) { queue.push_back(ERROR_FRAME); return; }
        Bytes resp = {(uint8_t)(c.cmd + 1)};
        Bytes r = respond(c.cmd, c.params);
        resp.insert(resp.end(), r.begin(), r.end());
        Bytes f = frame(0xD5, resp);
        if (badDcs) f[f.size() - 2]++;
        queue.push_back(f);
    }

    void _read(uint8_t* buf, size_t len) override {
        reads.push_back(len);
        memset(buf, 0, len);
        if (queue.empty()) return;
        buf[0] = 0x01;
        if (len == 1) return;
        Bytes item = queue.front();
        queue.pop_front();
        if (item != ACK) last = item;
        for (size_t i = 1; i < len; i++) buf[i] = i - 1 < item.size() ? item[i - 1] : 0xFF;
    }
};

class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    bool onEdge(Handler h, uint8_t) override { handler = h; return true; }
    void offEdge(Handler h) override { if (handler == h) handler = nullptr; }
    bool isHigh() override { return true; }
};

static int hits = 0;
static void onFrame() { hits++; }

static bool cmdIs(const Command& c, uint8_t cmd, const Bytes& params) {
    return c.cmd == cmd && c.params == params;
}

int main() {
    // --- Minimal init ---
    {
        PN532Sim sim;
        PN532Minimal m(sim);
        check_true(m.init(), "init_ok");
        check_true(sim.commands.size() == 3 && sim.commands[0].cmd == 0x02 && sim.commands[1].cmd == 0x14 &&
                   sim.commands[2].cmd == 0x32, "init_commands");
        check_true(sim.commands[1].params == Bytes({0x01, 0x14, 0x01}), "init_sam");
        check_true(sim.commands[2].params == Bytes({0x05, 0xFF, 0x01, 0x02}), "init_retries");
        check_true(sim.writes()[0] == Bytes({0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00}), "frame_get_firmware_version");
        check_true(sim.writes()[1] == NACK, "init_nack_after_header");
        size_t acks = 0, full = 0;
        for (size_t r : sim.reads) { if (r == 7) acks++; if (r == 14) full++; }
        check_true(acks == 3, "ack_read_7");
        check_true(full == 1, "response_full_read_len");

        uint8_t uid[10];
        check_true(m.read_uid(uid) == 7 && memcmp(uid, sim.uid.data(), 7) == 0, "read_uid_value");
        check_true(cmdIs(sim.commands[3], 0x4A, {0x01, 0x00}), "read_uid_list_params");
        check_true(cmdIs(sim.commands[4], 0x52, {0x01}), "read_uid_release");
        sim.card = false;
        check_true(m.read_uid(uid) == 0 && m.last_error() == 0, "read_uid_no_card");
        sim.noResponse = true;
        check_true(m.read_uid(uid, 10) == 0 && m.last_error() == 0, "read_uid_timeout_none");
        check_true(sim.writes().back() == ACK, "read_uid_timeout_abort");
        sim.noResponse = false;
        sim.errorFrame = true;
        check_true(m.read_uid(uid) == 0 && m.last_error() == PN532Minimal::ERR_ERROR_FRAME, "error_frame");
        sim.errorFrame = false;
        sim.badDcs = true;
        sim.card = true;
        check_true(m.read_uid(uid) == 0 && m.last_error() == PN532Minimal::ERR_FRAME, "bad_dcs");
    }
    {
        PN532Sim sim;
        sim.ic = 0x31;
        PN532Minimal m(sim);
        check_true(!m.init() && m.last_error() == PN532Minimal::ERR_IC, "init_bad_ic");
    }
    {
        PN532Sim sim;
        sim.failNextWrites(3);
        PN532Minimal m(sim);
        check_true(m.init() && sim.commands.size() == 3, "wake_write_retry");
    }
    {
        PN532Sim sim;
        sim.noAck = true;
        PN532Minimal m(sim);
        check_true(!m.init() && m.last_error() == PN532Minimal::ERR_NO_ACK, "no_ack");
        check_true(sim.commands.size() == 3, "no_ack_three_attempts");
    }

    // --- Full ---
    PN532Sim sim;
    PN532Full f(sim);
    f.init();
    PN532FirmwareVersion fw;
    check_true(f.firmware_version(fw) && fw.ic == 0x32 && fw.version == 1 && fw.revision == 6, "fw_ic");
    check_true(fw.support == 7 && fw.iso14443a && fw.iso14443b && fw.iso18092, "fw_support");
    PN532GeneralStatus gs;
    check_true(f.general_status(gs) && gs.error == 0 && gs.field && gs.targets == 1 && gs.sam_status == 0x01,
               "general_status");

    PN532Target t[2];
    sim.useListResponse = true;
    sim.listResponse = {2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02,
                        2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9};
    int n = f.list_passive_targets(t, PN532Full::BAUD_106_A, 2);
    check_true(n == 2, "list_two_targets");
    const uint8_t uidA[4] = {1, 2, 3, 4};
    check_true(t[0].tg == 1 && t[0].sens_res == 0x0004 && t[0].sel_res == 0x20 && t[0].uid_len == 4 &&
               memcmp(t[0].uid, uidA, 4) == 0, "list_target_a");
    const uint8_t ats[5] = {5, 0x75, 0x77, 0x81, 0x02};
    check_true(t[0].ats_len == 5 && memcmp(t[0].ats, ats, 5) == 0, "list_target_ats");
    check_true(t[1].tg == 2 && t[1].sens_res == 0x0044 && t[1].uid_len == 7 && t[1].uid[6] == 9 && t[1].ats_len == 0,
               "list_target_b");
    check_true(cmdIs(sim.commands.back(), 0x4A, {0x02, 0x00}), "list_params");
    sim.listResponse = {1, 1, 20, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC};
    const uint8_t felicaInit[5] = {0x00, 0xFF, 0xFF, 0x01, 0x00};
    n = f.list_passive_targets(t, PN532Full::BAUD_212_F, 1, felicaInit, 5);
    check_true(n == 1 && t[0].uid_len == 8 && t[0].uid[0] == 1 && t[0].uid[7] == 8 && t[0].ats_len == 10 &&
               t[0].ats[0] == 0x10 && t[0].ats[9] == 0xFC, "list_felica");
    check_true(cmdIs(sim.commands.back(), 0x4A, {0x01, 0x01, 0x00, 0xFF, 0xFF, 0x01, 0x00}), "list_felica_params");
    sim.listResponse = {1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00};
    const uint8_t afi[1] = {0x00};
    n = f.list_passive_targets(t, PN532Full::BAUD_106_B, 1, afi, 1);
    check_true(n == 1 && t[0].uid_len == 4 && t[0].uid[0] == 0xA1 && t[0].uid[3] == 0xA4 && t[0].ats_len == 1 &&
               t[0].ats[0] == 0x00, "list_type_b");
    sim.listResponse = {0};
    check_true(f.list_passive_targets(t) == 0, "list_empty");
    sim.useListResponse = false;

    uint8_t block[16];
    check_true(f.mifare_read_block(1, 4, block) && block[0] == 4 && block[15] == 19, "mifare_read_block");
    check_true(cmdIs(sim.commands.back(), 0x40, {0x01, 0x30, 0x04}), "mifare_read_cmd");
    const uint8_t key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    check_true(f.mifare_authenticate(1, 7, key, sim.uid.data(), 7), "mifare_auth");
    check_true(cmdIs(sim.commands.back(), 0x40, {0x01, 0x60, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                                                 0x33, 0x44, 0x55, 0x66}), "mifare_auth_payload");
    const uint8_t uid4[4] = {0xDE, 0xAD, 0xBE, 0xEF};
    f.mifare_authenticate(1, 7, key, uid4, 4, true);
    check_true(sim.commands.back().params[1] == 0x61, "mifare_auth_key_b");
    uint8_t zeros[16] = {0};
    f.mifare_write_block(1, 4, zeros);
    Bytes wexp = {0x01, 0xA0, 0x04};
    wexp.insert(wexp.end(), 16, 0);
    check_true(cmdIs(sim.commands.back(), 0x40, wexp), "mifare_write_cmd");
    check_true(f.ntag_read_page(1, 4, block) && block[0] == 4, "ntag_read_page");
    const uint8_t page[4] = {1, 2, 3, 4};
    f.ntag_write_page(1, 5, page);
    check_true(cmdIs(sim.commands.back(), 0x40, {0x01, 0xA2, 0x05, 1, 2, 3, 4}), "ntag_write_cmd");
    uint8_t out[8];
    const uint8_t thru[3] = {1, 2, 3};
    n = f.communicate_thru(thru, 3, out, sizeof(out));
    check_true(n == 3 && out[0] == 3 && out[2] == 1, "communicate_thru");

    sim.status = 0x14;
    const uint8_t rd[2] = {0x30, 0x00};
    check_true(f.data_exchange(1, rd, 2, out, sizeof(out)) < 0 && f.last_error() == 0x14, "data_exchange_status");
    check_true(!f.release(1) && f.last_error() == 0x14, "release_status");
    sim.status = 0;
    check_true(f.release() && cmdIs(sim.commands.back(), 0x52, {0x00}), "release_all");
    check_true(f.select(1) && cmdIs(sim.commands.back(), 0x54, {0x01}), "select");
    check_true(f.deselect(1) && cmdIs(sim.commands.back(), 0x44, {0x01}), "deselect");

    f.set_rf_field(false);
    check_true(cmdIs(sim.commands.back(), 0x32, {0x01, 0x00}), "rf_field_off");
    f.set_rf_field(true, true);
    check_true(cmdIs(sim.commands.back(), 0x32, {0x01, 0x03}), "rf_field_on_rfca");
    f.set_max_retries(0xFF);
    check_true(cmdIs(sim.commands.back(), 0x32, {0x05, 0xFF, 0x01, 0xFF}), "max_retries");
    f.write_register(0x6302, 0x5A);
    check_true(cmdIs(sim.commands.back(), 0x08, {0x63, 0x02, 0x5A}), "write_register");
    uint8_t v = 0;
    check_true(f.read_register(0x6302, v) && v == 0x5A, "read_register");
    check_true(cmdIs(sim.commands.back(), 0x06, {0x63, 0x02}), "read_register_cmd");
    check_true(f.self_test(), "self_test_echo");
    check_true(cmdIs(sim.commands.back(), 0x00, {0x00, 'P', 'E', 'R', 'I', 'P', 'H', '0', '1'}), "self_test_echo_cmd");
    check_true(f.self_test(0x01), "self_test_rom");
    uint8_t resp[8];
    n = f.send_command(0x02, nullptr, 0, resp, sizeof(resp));
    check_true(n == 4 && resp[0] == 0x32, "send_command");
    check_true(f.power_down() && cmdIs(sim.commands.back(), 0x16, {0x20}), "power_down");

    check_true(f.start_command(0x02), "start_command");
    check_true(f.wait_ready(10), "wait_ready_pending");
    check_true(f.poll_interrupt(), "poll_interrupt_ready");
    n = f.read_response(0x02, resp, sizeof(resp));
    check_true(n == 4 && resp[0] == 0x32, "read_response");
    check_true(!f.poll_interrupt(), "poll_interrupt_idle");
    check_true(!f.wait_ready(5), "wait_ready_idle");

    FakePin pin;
    f.on_interrupt(onFrame, &pin);
    check_true(pin.handler != nullptr, "on_interrupt_registers");
    pin.handler();
    check_true(hits == 1, "on_interrupt_callback");
    f.off_interrupt();
    check_true(pin.handler == nullptr, "off_interrupt");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
