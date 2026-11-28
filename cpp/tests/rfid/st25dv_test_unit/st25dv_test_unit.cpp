#include <stdio.h>
#include <string.h>
#include "I2CConnectionMock.h"
#include "St25dv.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static uint8_t reg(const I2CConnectionMock& c, uint16_t a) {
    auto it = c.registers().find(a);
    return it == c.registers().end() ? 0 : it->second;
}

static bool frame_is(const std::vector<uint8_t>& w, const uint8_t* expect, size_t n) {
    return w.size() == n && memcmp(w.data(), expect, n) == 0;
}

class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    uint8_t trigger = 0xFF;
    bool onEdge(Handler h, uint8_t t) override { handler = h; trigger = t; return true; }
    void offEdge(Handler h) override { if (h == handler) handler = nullptr; }
    bool isHigh() override { return true; }
};

static uint8_t got_status = 0;
static int got_count = 0;
static void on_event(uint8_t s) { got_status = s; got_count++; }

struct Tag {
    I2CConnectionMock user;
    I2CConnectionMock system;
    Tag(uint8_t ic_ref, uint8_t mem_lo, uint8_t mem_hi) : user(2), system(2) {
        user.setAddressWidth(2);
        system.setAddressWidth(2);
        system.setRegister(0x0014, {mem_lo, mem_hi, 0x03, ic_ref});
        system.setRegister(0x0018, {0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0});
        system.setRegister(0x0020, {0x12});
        system.setRegister(0x0000, {0x88});
        system.setRegister(0x0002, {0x01});
    }
};

int main() {
    // --- Minimal ---
    {
        Tag t(0x26, 0xFF, 0x01);
        ST25DVMinimal tag(t.user, t.system);
        check_true(t.system.writes().empty() && t.user.writes().empty(), "ctor_no_traffic");
        check_true(tag.init(), "init");
        static const uint8_t id_addr[] = {0x00, 0x14};
        check_true(t.system.writes().size() == 1 && frame_is(t.system.writes()[0], id_addr, 2), "init_reads_ident");
        check_true(t.user.writes().empty(), "init_no_user_traffic");

        t.user.setRegister(0x0100, {1, 2, 3});
        uint8_t buf[3] = {0};
        check_true(tag.read(0x0100, buf, 3) && buf[0] == 1 && buf[2] == 3, "read");
        static const uint8_t ra[] = {0x01, 0x00};
        check_true(frame_is(t.user.writes().back(), ra, 2), "read_address_bytes");
        check_true(!tag.read(2047, buf, 2), "read_out_of_range");

        uint8_t data[40];
        for (int i = 0; i < 40; i++) data[i] = (uint8_t)i;
        t.user.clearWrites();
        check_true(tag.write(0x0008, data, 40), "write_ok");
        std::vector<std::vector<uint8_t>> frames, polls;
        for (const auto& w : t.user.writes()) (w.size() > 2 ? frames : polls).push_back(w);
        check_true(frames.size() == 3 && polls.size() == 3, "write_chunk_and_poll_count");
        check_true(frames[0].size() == 10 && frames[0][1] == 0x08 && frames[0][2] == 0, "write_first_chunk_to_16_boundary");
        check_true(frames[1].size() == 18 && frames[1][1] == 0x10 && frames[1][2] == 8, "write_second_chunk");
        check_true(frames[2].size() == 18 && frames[2][1] == 0x20 && frames[2][2] == 24, "write_third_chunk");
        bool same = true;
        for (int i = 0; i < 40; i++) same = same && reg(t.user, 0x08 + i) == i;
        check_true(same, "write_applied");
        check_true(!tag.write(2040, data, 9), "write_out_of_range");
        t.user.failNextWrites(1000, 1);
        check_true(!tag.write(0, data, 1), "write_timeout_false");
        t.user.failNextWrites(0);
    }
    {
        Tag t4(0x24, 0x7F, 0x00);
        ST25DVFull f4(t4.user, t4.system);
        f4.init();
        check_true(f4.memory_size() == 512, "size_04k");
        Tag t64(0x26, 0xFF, 0x07);
        ST25DVFull f64(t64.user, t64.system);
        f64.init();
        check_true(f64.memory_size() == 8192, "size_64k");
        uint8_t m[2] = {1, 2};
        check_true(f64.write_ndef(m, 2), "ndef_64k_write");
        static const uint8_t cc64[] = {0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, 0x03, 0xFF};
        bool ok = true;
        for (int i = 0; i < 8; i++) ok = ok && reg(t64.user, i) == cc64[i];
        check_true(ok, "ndef_cc_64k");
        uint8_t back[4];
        check_true(f64.read_ndef(back, 4) == 2 && back[1] == 2, "read_ndef_64k");
        uint8_t one = 1;
        f4.write_ndef(&one, 1);
        static const uint8_t cc4[] = {0xE1, 0x40, 0x40, 0x01};
        ok = true;
        for (int i = 0; i < 4; i++) ok = ok && reg(t4.user, i) == cc4[i];
        check_true(ok, "ndef_cc_04k");
    }

    // --- Full ---
    Tag t(0x26, 0xFF, 0x01);
    I2CConnectionMock& user = t.user;
    I2CConnectionMock& system = t.system;
    ST25DVFull tag(user, system);
    tag.init();
    check_true(tag.memory_size() == 2048, "memory_size");
    check_true(tag.ic_reference() == 0x26, "ic_reference");
    uint8_t uid[8];
    static const uint8_t uid_exp[] = {0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11};
    check_true(tag.uid(uid) && memcmp(uid, uid_exp, 8) == 0, "uid_msb_first");
    check_true(tag.ic_revision() == 0x12, "ic_revision");
    uint8_t b1;
    check_true(tag.read_system(0x0017, &b1, 1) && b1 == 0x26, "read_system");

    system.clearWrites();
    check_true(tag.write_system(0x0001, (uint8_t)5), "write_system_ok");
    static const uint8_t ws[] = {0x00, 0x01, 0x05};
    static const uint8_t poll0[] = {0x00, 0x00};
    check_true(frame_is(system.writes()[0], ws, 3), "write_system_frame");
    check_true(frame_is(system.writes()[1], poll0, 2), "write_system_ack_poll");

    // Password.
    const uint8_t pw[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    user.setRegister(0x2004, {0x01});
    system.clearWrites();
    check_true(tag.present_password(pw), "present_password_open");
    uint8_t pf[19] = {0x09, 0x00};
    memcpy(pf + 2, pw, 8); pf[10] = 0x09; memcpy(pf + 11, pw, 8);
    check_true(frame_is(system.writes()[0], pf, 19), "present_password_frame");
    check_true(tag.session_open(), "session_open");
    user.setRegister(0x2004, {0x00});
    check_true(!tag.present_password(pw), "present_password_closed");
    check_true(!tag.write_password(pw), "write_password_closed_false");
    user.setRegister(0x2004, {0x01});
    system.clearWrites();
    check_true(tag.write_password(pw), "write_password_ok");
    pf[10] = 0x07;
    check_true(frame_is(system.writes()[0], pf, 19), "write_password_frame");

    // Areas.
    system.clearWrites();
    tag.set_area_end(2, 0x1F);
    static const uint8_t ae[] = {0x00, 0x07, 0x1F};
    check_true(frame_is(system.writes()[0], ae, 3), "set_area_end");
    check_true(!tag.set_area_end(4, 1), "set_area_end_range");
    system.setRegister(0x000B, {0x00});
    tag.set_area_protection(3, 0x05, 0x03);
    check_true(reg(system, 0x0008) == 0x05, "area_protection_rf");
    check_true(reg(system, 0x000B) == 0x30, "area_protection_i2c");
    tag.set_area_protection(2, 0x0C, 0x01);
    check_true(reg(system, 0x000B) == 0x34, "area_protection_i2c_rmw");
    uint8_t rf = 0, i2c = 0;
    check_true(tag.get_area_protection(3, rf, i2c) && rf == 0x05 && i2c == 0x03, "get_area_protection");
    check_true(tag.get_area_protection(2, rf, i2c) && rf == 0x0C && i2c == 0x01, "get_area_protection_2");
    check_true(!tag.set_area_protection(5, 0, 0), "area_protection_range");
    check_true(!tag.set_area_protection(1, 0, 4), "area_protection_level");

    // GPO.
    tag.set_gpo(ST25DVFull::GPO_FIELD_CHANGE | ST25DVFull::GPO_RF_WRITE);
    check_true(reg(system, 0x0000) == 0xC8, "set_gpo");
    tag.set_gpo(ST25DVFull::GPO_FIELD_CHANGE, false);
    check_true(reg(system, 0x0000) == 0x08, "set_gpo_disabled");
    check_true(tag.get_gpo() == 0x08, "get_gpo");
    tag.set_gpo_runtime(true);
    check_true(reg(user, 0x2000) == 0x80, "set_gpo_runtime");
    tag.set_gpo_runtime(false);
    check_true(reg(user, 0x2000) == 0x00, "set_gpo_runtime_off");
    tag.set_interrupt_pulse(3);
    check_true(reg(system, 0x0001) == 3, "set_interrupt_pulse");
    check_true(!tag.set_interrupt_pulse(8), "interrupt_pulse_range");

    // EH, RF.
    user.setRegister(0x2002, {0x0C});
    check_true(tag.field_present(), "field_present");
    user.setRegister(0x2002, {0x08});
    check_true(!tag.field_present(), "field_absent");
    system.setRegister(0x0002, {0x01});
    system.clearWrites();
    tag.set_energy_harvesting(true);
    check_true(system.writes().size() == 1 && system.writes()[0].size() == 2, "eh_no_static_write");
    check_true(reg(user, 0x2002) == 0x01, "eh_dynamic");
    tag.set_energy_harvesting(false, true);
    check_true(reg(system, 0x0002) == 0x00, "eh_forced_static");
    check_true(reg(user, 0x2002) == 0x00, "eh_dynamic_off");
    tag.set_rf_state(false, true);
    check_true(reg(user, 0x2003) == 0x02, "rf_sleep");
    tag.set_rf_state(true);
    check_true(reg(user, 0x2003) == 0x01, "rf_disable");
    tag.set_rf_state();
    check_true(reg(user, 0x2003) == 0x00, "rf_normal");

    // Mailbox.
    system.setRegister(0x000D, {0x00});
    tag.enable_mailbox();
    check_true(reg(system, 0x000D) == 0x01, "mb_mode_static");
    check_true(reg(user, 0x2006) == 0x01, "mb_en");
    system.clearWrites();
    tag.enable_mailbox();
    bool reads_only = true;
    for (const auto& w : system.writes()) reads_only = reads_only && w.size() == 2;
    check_true(reads_only, "mb_mode_not_rewritten");
    user.setRegister(0x2006, {0x01 | 0x04 | 0x20});
    ST25DVMailboxStatus st = tag.mailbox_status();
    check_true(st.enabled, "mb_status_enabled");
    check_true(st.rf_put && !st.host_put, "mb_status_rf_put");
    check_true(st.rf_miss && !st.host_miss, "mb_status_rf_miss");
    uint8_t x = 'x';
    check_true(!tag.mailbox_write(&x, 1), "mb_write_busy");
    user.setRegister(0x2006, {0x01});
    uint8_t msg[100];
    for (int i = 0; i < 100; i++) msg[i] = (uint8_t)i;
    user.clearWrites();
    check_true(tag.mailbox_write(msg, 100), "mb_write_ok");
    const auto& mw = user.writes().back();
    check_true(mw.size() == 102 && mw[0] == 0x20 && mw[1] == 0x08 && mw[101] == 99, "mb_write_single_frame");
    check_true(!tag.mailbox_write(msg, 0), "mb_write_len");
    check_true(!tag.mailbox_write(msg, 257), "mb_write_len_max");
    user.setRegister(0x2006, {0x00});
    check_true(!tag.mailbox_write(&x, 1), "mb_write_disabled");
    uint8_t mb[256];
    check_true(tag.mailbox_read(mb, sizeof(mb)) == 0, "mb_read_empty");
    user.setRegister(0x2006, {0x05});
    user.setRegister(0x2007, {19});
    for (int i = 0; i < 20; i++) user.setRegister((uint16_t)(0x2008 + i), {(uint8_t)(0xA0 + i)});
    size_t n = tag.mailbox_read(mb, sizeof(mb));
    check_true(n == 20 && mb[0] == 0xA0 && mb[19] == 0xB3, "mb_read");
    check_true(tag.mailbox_read(mb, 10) == 0, "mb_read_capacity");
    tag.disable_mailbox();
    check_true(reg(user, 0x2006) == 0x00, "mb_disable");
    tag.set_mailbox_watchdog(4);
    check_true(reg(system, 0x000E) == 4, "mb_wdg");
    check_true(!tag.set_mailbox_watchdog(8), "mb_wdg_range");

    // CC lock, NDEF.
    tag.lock_ccfile();
    check_true(reg(system, 0x000C) == 0x03, "lock_ccfile");
    tag.lock_ccfile(false);
    check_true(reg(system, 0x000C) == 0x00, "unlock_ccfile");
    static const uint8_t ndef[] = {0xD1, 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x48};
    check_true(tag.write_ndef(ndef, sizeof(ndef)), "write_ndef");
    check_true(reg(user, 0) == 0xE1 && reg(user, 1) == 0x40 && reg(user, 2) == 0xFF && reg(user, 3) == 0x01, "ndef_cc_16k");
    check_true(reg(user, 4) == 0x03 && reg(user, 5) == sizeof(ndef), "ndef_tlv");
    check_true(reg(user, 6 + sizeof(ndef)) == 0xFE, "ndef_terminator");
    uint8_t nb[400];
    check_true(tag.read_ndef(nb, sizeof(nb)) == sizeof(ndef) && memcmp(nb, ndef, sizeof(ndef)) == 0, "read_ndef");
    static uint8_t long_msg[300];
    check_true(tag.write_ndef(long_msg, 300), "write_ndef_long");
    check_true(reg(user, 4) == 0x03 && reg(user, 5) == 0xFF && reg(user, 6) == 0x01 && reg(user, 7) == 0x2C, "ndef_long_tlv");
    check_true(tag.read_ndef(nb, sizeof(nb)) == 300, "read_ndef_long");
    static uint8_t too_big[2048];
    check_true(!tag.write_ndef(too_big, sizeof(too_big)), "ndef_too_large");
    user.setRegister(0x0000, {0x00});
    check_true(tag.read_ndef(nb, sizeof(nb)) == 0, "read_ndef_no_cc");

    user.failNextWrites(2);
    check_true(tag.wait_write_complete(), "wait_write_complete_after_nacks");
    user.failNextWrites(1000);
    check_true(!tag.wait_write_complete(), "wait_write_complete_timeout");
    user.failNextWrites(0);

    // Interrupts.
    system.setRegister(0x0000, {0x80});
    tag.enable_interrupt(ST25DVSource::FIELD_RISING | ST25DVSource::RF_WRITE);
    check_true(reg(system, 0x0000) == 0xC8, "enable_interrupt_gpo");
    check_true(reg(user, 0x2000) == 0x80, "enable_interrupt_gpo_en");
    tag.enable_interrupt(ST25DVSource::RF_PUT_MSG | ST25DVSource::RF_USER);
    check_true(reg(system, 0x0000) == 0xD9, "enable_interrupt_mapping");
    tag.disable_interrupt(ST25DVSource::RF_WRITE);
    check_true(reg(system, 0x0000) == 0x99, "disable_interrupt");
    user.setRegister(0x2005, {0x90});
    check_true(tag.poll_interrupt() == 0x90, "poll_interrupt");

    FakePin pin;
    tag.on_interrupt(on_event, &pin);
    check_true(pin.trigger == InputPin::kFalling, "on_interrupt_falling");
    user.setRegister(0x2005, {0x10});
    pin.handler();
    check_true(got_count == 1 && got_status == 0x10, "on_interrupt_dispatch");
    user.setRegister(0x2005, {0x00});
    pin.handler();
    check_true(got_count == 1, "on_interrupt_no_spurious");
    tag.off_interrupt();
    check_true(pin.handler == nullptr, "off_interrupt");
    {
        Tag th(0x26, 0xFF, 0x01);
        ST25DVFull fh(th.user, th.system, true);
        FakePin ph;
        fh.on_interrupt(on_event, &ph);
        check_true(ph.trigger == InputPin::kRising, "on_interrupt_rising_jf");
        fh.off_interrupt();
    }

    // Read retry.
    user.failNextWrites(2);
    user.setRegister(0x0040, {0x77});
    uint8_t r = 0;
    check_true(tag.read(0x0040, &r, 1) && r == 0x77, "read_retries_after_nacks");
    user.failNextWrites(3);
    check_true(!tag.read(0x0040, &r, 1), "read_fails_after_retries");
    user.failNextWrites(0);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
