#include <stdio.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "PCA9541.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Simulates how the chip recomputes the read-only N bits after a CONTROL write (this master's view).
class SimMock : public I2CConnectionMock {
protected:
    void _write(const uint8_t* data, size_t len) override {
        uint8_t prior = registers().count(0x01) ? registers().at(0x01) : 0;
        I2CConnectionMock::_write(data, len);
        if (len == 2 && data[0] == 0x01) {
            uint8_t w = data[1];
            uint8_t mybus = w & 1, buson = (w >> 2) & 1;
            uint8_t nmybusPrior = (prior >> 1) & 1, nbusonPrior = (prior >> 3) & 1;
            bool control = (mybus == nmybusPrior);
            bool on = (buson != nbusonPrior);
            uint8_t nmybus = control ? mybus : (uint8_t)(1 - mybus);
            uint8_t nbuson = on ? (uint8_t)(1 - buson) : buson;
            setRegister(0x01, {(uint8_t)((w & 0xF5) | (nmybus << 1) | (nbuson << 3))});
        }
    }
};

static void preload(I2CConnectionMock& m, uint8_t control = 0x04, uint8_t ie = 0, uint8_t istat = 0) {
    m.setRegister(0x00, {ie});  m.setRegister(0x01, {control});  m.setRegister(0x02, {istat});
    m.setRegister(0x10, {ie});  m.setRegister(0x11, {control});  m.setRegister(0x12, {istat});
}

static size_t dataWrites(const I2CConnectionMock& m) {
    size_t n = 0;
    for (const auto& w : m.writes()) if (w.size() > 1) n++;
    return n;
}

static std::vector<uint8_t> lastDataWrite(const I2CConnectionMock& m) {
    for (size_t i = m.writes().size(); i-- > 0;) if (m.writes()[i].size() > 1) return m.writes()[i];
    return {};
}

static int g_calls = 0, g_status = 0;
static void onInt(uint8_t s) { g_calls++; g_status = s; }

int main() {
    // Init: bus check + ISTAT read, nothing written.
    {
        SimMock m; preload(m);
        PCA9541Full chip(m);
        check_true(dataWrites(m) == 0, "init_writes_nothing");
        check_true(m.writes().size() == 2 && m.writes()[0][0] == 0x01 && m.writes()[1][0] == 0x02, "init_reads_control_and_istat");
        check_true(chip.present(), "present_when_acknowledged");
    }
    {
        SimMock m; preload(m);
        m.failNextWrites(1);
        PCA9541Minimal chip(m);
        check_true(!chip.present(), "not_present_when_nacked");
    }
    // Table 12: the byte acquire writes for each CONTROL read value.
    {
        const uint8_t read[]  = {0x0, 0x1, 0x2, 0x3, 0x5, 0x6, 0x9, 0xA, 0xC, 0xD, 0xE, 0xF};
        const uint8_t want[]  = {0x4, 0x4, 0x5, 0x5, 0x4, 0x5, 0x0, 0x1, 0x0, 0x0, 0x1, 0x1};
        bool ok = true;
        for (int i = 0; i < 12; i++) {
            SimMock m; preload(m, read[i]);
            PCA9541Full chip(m);
            chip.acquire(false, 0.0f);
            auto w = lastDataWrite(m);
            ok = ok && w.size() == 2 && w[0] == 0x01 && w[1] == want[i];
        }
        check_true(ok, "acquire_follows_table12");
        const uint8_t keep[] = {0x4, 0x7, 0x8, 0xB};
        ok = true;
        for (int i = 0; i < 4; i++) {
            SimMock m; preload(m, keep[i]);
            PCA9541Full chip(m);
            ok = ok && chip.acquire(false, 0.0f);
        }
        check_true(ok, "acquire_keeps_control");
    }
    // hasControl: MYBUS == NMYBUS and BUSON != NBUSON
    {
        const uint8_t c[] = {0x04, 0x0A, 0x00, 0x02, 0x07, 0x05};
        const bool v[]    = {true, false, false, false, true, false};
        bool ok = true;
        for (int i = 0; i < 6; i++) { SimMock m; preload(m, c[i]); PCA9541Full chip(m); ok = ok && chip.hasControl() == v[i]; }
        check_true(ok, "has_control_values");
    }
    // acquire with bus init waits for BUSINIT in ISTAT
    {
        SimMock m; preload(m, 0x0A, 0, 0x02);
        PCA9541Full chip(m);
        check_true(chip.acquire(true), "acquire_bus_init_ok");
        auto w = lastDataWrite(m);
        check_true(w.size() == 2 && w[1] == 0x11, "acquire_bus_init_sets_bit");
        SimMock m2; preload(m2, 0x0A, 0, 0x00);
        PCA9541Full chip2(m2);
        check_true(!chip2.acquire(true, 3.0f), "acquire_bus_init_times_out");
    }
    // release
    {
        SimMock m; preload(m, 0x04);
        PCA9541Full chip(m);
        chip.release();
        auto w = lastDataWrite(m);
        check_true(w[1] == 0x05 && !chip.hasControl(), "release_write");
    }
    // connect / disconnect keep ownership
    {
        SimMock m; preload(m, 0x0D);
        PCA9541Full chip(m);
        chip.connect();
        check_true(lastDataWrite(m)[1] == 0x01, "connect_write");
        SimMock m2; preload(m2, 0x04);
        PCA9541Full chip2(m2);
        chip2.disconnect();
        check_true(lastDataWrite(m2)[1] == 0x00 && !chip2.isConnected(), "disconnect_write");
        chip2.connect();
        check_true(chip2.isConnected(), "connect_makes_connected");
    }
    // getControl / status
    {
        SimMock m; preload(m, 0xD4);
        PCA9541Full chip(m);
        PCA9541Full::Status s = chip.status();
        check_true(chip.getControl() == 0xD4 && s.hasControl && s.connected && s.busInitRequested && s.ownTest && s.otherTest, "status_decode");
        SimMock m2; preload(m2, 0x0A);
        PCA9541Full chip2(m2);
        s = chip2.status();
        check_true(!s.hasControl && s.connected, "status_no_control");
    }
    // recoverBus
    {
        SimMock m; preload(m, 0x0A, 0, 0x02);
        PCA9541Full chip(m);
        check_true(chip.recoverBus() && (lastDataWrite(m)[1] & 0x10), "recover_bus");
    }
    // interrupt mask
    {
        SimMock m; preload(m);
        PCA9541Full chip(m);
        chip.setInterruptMask(true, false, false, true);
        check_true(m.registers().at(0x00) == 0x09 && chip.getInterruptMask() == 0x09, "set_interrupt_mask");
        chip.setInterruptMask();
        check_true(m.registers().at(0x00) == 0x00, "set_interrupt_mask_defaults_unmask");
        chip.disableInterrupt(PCA9541Full::SOURCE_BUS_INIT);
        check_true(m.registers().at(0x00) == 0x02, "disable_interrupt");
        chip.disableInterrupt(PCA9541Full::SOURCE_INT_IN);
        chip.enableInterrupt(PCA9541Full::SOURCE_BUS_INIT);
        check_true(m.registers().at(0x00) == 0x01, "enable_interrupt_clears_one_bit");
        size_t n = dataWrites(m);
        chip.enableInterrupt(PCA9541Full::SOURCE_TEST);
        check_true(dataWrites(m) == n, "test_source_needs_no_enable");
    }
    // functional INT test keeps the bus bits
    {
        SimMock m; preload(m, 0x0D);
        PCA9541Full chip(m);
        uint8_t bits = m.registers().at(0x01) & 0x05;
        chip.testInterrupt(true);
        check_true((m.registers().at(0x01) & 0xC5) == (bits | 0x40), "test_interrupt_own");
        chip.testInterrupt(false, true);
        check_true((m.registers().at(0x01) & 0xC0) == 0x80, "test_interrupt_other");
        chip.testInterrupt(true, true);
        check_true((m.registers().at(0x01) & 0xC0) == 0xC0, "test_interrupt_both");
        chip.testInterrupt();
        check_true((m.registers().at(0x01) & 0xC0) == 0 && (m.registers().at(0x01) & 0x05) == bits, "test_interrupt_clear");
    }
    // readAll is one auto-increment read for three bytes
    {
        SimMock m; preload(m, 0x04, 0x03, 0x08);
        PCA9541Full chip(m);
        size_t n = m.writes().size();
        uint8_t ie = 0, c = 0, s = 0;
        chip.readAll(ie, c, s);
        check_true(ie == 0x03 && c == 0x04 && s == 0x08, "read_all_values");
        check_true(m.writes().size() == n + 1 && m.writes().back()[0] == 0x10, "read_all_auto_increment");
    }
    // pollInterrupt / callback
    {
        SimMock m; preload(m, 0x04, 0, 0x0A);
        PCA9541Full chip(m);
        check_true(chip.pollInterrupt() == 0x0A, "poll_interrupt");
        chip.onInterrupt(onInt);
        chip.service();
        m.setRegister(0x02, {0x00});
        chip.service();
        check_true(g_calls == 1 && g_status == 0x0A, "service_dispatches_callback");
        chip.offInterrupt();
        m.setRegister(0x02, {0x01});
        chip.service();
        check_true(g_calls == 1, "off_interrupt_stops_delivery");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
