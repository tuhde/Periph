// Unit test for DisplayConnectionBase, SoftSPI and DisplayConnectionMock — no hardware.
#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <string>
#include <vector>
#include "DisplayConnectionBase.h"
#include "DisplayConnectionMock.h"
#include "SoftSPI.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

// Shared ordered log of everything the fakes see.
static std::vector<std::string> g_log;

static std::string hex(const uint8_t* d, size_t n) {
    static const char* h = "0123456789ABCDEF";
    std::string s;
    for (size_t i = 0; i < n; i++) { s += h[d[i] >> 4]; s += h[d[i] & 15]; }
    return s;
}

class LogOut : public OutputPin {
public:
    explicit LogOut(const char* name) : _name(name) {}
    void set(bool high) override { level = high; g_log.push_back(_name + (high ? "=1" : "=0")); }
    bool level = false;
private:
    std::string _name;
};

// Level script: successive isHigh() calls return the script entries; the last repeats.
class ScriptIn : public InputPin {
public:
    explicit ScriptIn(std::vector<bool> script) : _s(script) {}
    bool onEdge(Handler, uint8_t = kFalling) override { return false; }
    void offEdge(Handler) override {}
    bool isHigh() override {
        bool v = _s[_i < _s.size() ? _i : _s.size() - 1];
        _i++;
        return v;
    }
    size_t polls() const { return _i; }
private:
    std::vector<bool> _s;
    size_t _i = 0;
};

// Concrete connection with a simulated clock: delays advance time, nothing sleeps.
class TestConnection : public DisplayConnectionBase {
public:
    TestConnection(OutputPin* cs, OutputPin* dc, OutputPin* res, InputPin* busy,
                   DisplayWireMode wm = DisplayWireMode::kSimplex,
                   uint32_t pulseUs = 200, uint32_t settleMs = 10,
                   OutputPin* en = nullptr, bool enHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wm, pulseUs, settleMs, en, enHigh) {}

    std::vector<uint8_t> readBytes;   // bytes the "chip" returns
    uint64_t nowUs = 0;
    uint32_t lastDelayUs = 0;
    uint32_t lastDelayMs = 0;

protected:
    void spiWrite(const uint8_t* d, size_t n) override {
        csLow();
        g_log.push_back("W:" + hex(d, n));
        csHigh();
    }
    void spiRead(uint8_t* b, size_t n) override {
        csLow();
        for (size_t i = 0; i < n; i++) b[i] = _pos < readBytes.size() ? readBytes[_pos++] : 0;
        g_log.push_back("R:" + std::to_string(n));
        csHigh();
    }
    void delayUs(uint32_t us) override { lastDelayUs = us; nowUs += us; g_log.push_back("us"); }
    void delayMs(uint32_t ms) override { lastDelayMs = ms; nowUs += uint64_t(ms) * 1000; }
    uint32_t nowMs() override { return static_cast<uint32_t>(nowUs / 1000); }

private:
    size_t _pos = 0;
};

static bool logIs(const std::vector<std::string>& want) { return g_log == want; }

int main() {
    // ---- framing ------------------------------------------------------------------
    {
        LogOut cs("cs"), dc("dc"), res("res");
        ScriptIn busy({false});
        TestConnection c(&cs, &dc, &res, &busy);
        g_log.clear();
        const uint8_t params[] = {0x01, 0x02};
        c.sendCommand(0x12, params, 2);
        check_true(logIs({"dc=0", "cs=0", "W:12", "cs=1", "dc=1", "cs=0", "W:0102", "cs=1"}),
                   "sendCommand: dc low + CS window for the command, dc high + CS window for the parameters");
        g_log.clear();
        c.sendCommand(0x04);
        check_true(logIs({"dc=0", "cs=0", "W:04", "cs=1"}), "sendCommand without data is one transfer");
        g_log.clear();
        const uint8_t px[] = {0xFF, 0xFF, 0xFF};
        c.sendData(px, 3);
        check_true(logIs({"dc=1", "cs=0", "W:FFFFFF", "cs=1"}), "sendData: dc high, one CS window");
        check_true(c.valid(), "valid by default");
    }
    // ---- constructor idle levels ----------------------------------------------------
    {
        LogOut cs("cs"), dc("dc"), res("res");
        ScriptIn busy({false});
        g_log.clear();
        TestConnection c(&cs, &dc, &res, &busy);
        check_true(logIs({"cs=1", "dc=0", "res=1"}), "idle levels: CS high, D/C low, RES high");
    }
    // ---- reset ------------------------------------------------------------------------
    {
        LogOut cs("cs"), dc("dc"), res("res");
        ScriptIn busy({false});
        TestConnection c(&cs, &dc, &res, &busy, DisplayWireMode::kSimplex, 500, 2);
        g_log.clear();
        c.reset();
        check_true(logIs({"res=0", "us", "res=1"}), "reset: RES low, pulse delay, RES high");
        check_true(c.lastDelayUs == 500 && c.lastDelayMs == 2, "reset uses pulse and settle times");
    }
    // ---- waitBusy / busyLevel --------------------------------------------------------
    {
        LogOut cs("cs"), dc("dc"), res("res");
        ScriptIn lowIsBusy({false, false, true});
        TestConnection c(&cs, &dc, &res, &lowIsBusy);
        check_true(c.waitBusy(false, 1000) && lowIsBusy.polls() == 3, "waitBusy active-low returns once BUSY goes high");
        ScriptIn highIsBusy({true, true, false});
        TestConnection d(&cs, &dc, &res, &highIsBusy);
        check_true(d.waitBusy(true, 1000) && highIsBusy.polls() == 3, "waitBusy active-high returns once BUSY goes low");
        ScriptIn stuck({false});
        TestConnection e(&cs, &dc, &res, &stuck);
        check_true(!e.waitBusy(false, 50), "waitBusy times out when BUSY stays active");
        check_true(e.nowUs >= 50000, "timeout waited at least timeoutMs");
        ScriptIn ready({true});
        TestConnection f(&cs, &dc, &res, &ready);
        check_true(f.waitBusy(false, 10) && ready.polls() == 1, "waitBusy returns at once when already ready");
        check_true(f.busyLevel(), "busyLevel returns the raw level");
    }
    // ---- read paths ---------------------------------------------------------------------
    {
        LogOut cs("cs"), dc("dc"), res("res");
        ScriptIn busy({false});
        TestConnection simplex(&cs, &dc, &res, &busy);
        uint8_t b[2] = {0xAA, 0xAA};
        check_true(!simplex.readData(b, 2) && b[0] == 0xAA, "readData in kSimplex returns false and reads nothing");

        TestConnection hd(&cs, &dc, &res, &busy, DisplayWireMode::kHalfDuplexPin);
        hd.readBytes = {0x13, 0x07};
        g_log.clear();
        check_true(hd.readData(b, 2) && b[0] == 0x13 && b[1] == 0x07, "readData returns the bytes");
        check_true(logIs({"dc=1", "cs=0", "R:1", "cs=1", "cs=0", "R:1", "cs=1"}),
                   "readData: dc high, one CS window per byte");
    }
    // ---- enable / disable -------------------------------------------------------------
    {
        LogOut cs("cs"), dc("dc"), res("res"), en("en");
        ScriptIn busy({false});
        TestConnection c(&cs, &dc, &res, &busy, DisplayWireMode::kHalfDuplexPin, 200, 10, &en, false);
        c.disable();
        check_true(en.level && !c.isEnabled(), "disable drives an active-low EN pin high");
        g_log.clear();
        c.sendCommand(0x12);
        c.sendData(reinterpret_cast<const uint8_t*>("\x01"), 1);
        c.reset();
        check_true(g_log.empty(), "disabled connection is a no-op on the bus");
        uint8_t z[3] = {9, 9, 9};
        check_true(c.readData(z, 3) && z[0] == 0 && z[2] == 0, "disabled readData returns zeros");
        c.enable();
        check_true(!en.level && c.isEnabled(), "enable drives an active-low EN pin low");
    }
    // ---- optional RES / BUSY (OLED) ------------------------------------------------------
    {
        LogOut cs("cs"), dc("dc");
        TestConnection oled(&cs, &dc, nullptr, nullptr);
        g_log.clear();
        oled.reset();
        check_true(g_log.empty(), "reset() without a RES pin is a no-op");
        check_true(oled.waitBusy(true, 10), "waitBusy() without a BUSY pin returns true at once");
        check_true(!oled.busyLevel(), "busyLevel() without a BUSY pin is false");
        oled.sendCommand(0xAF);
        check_true(logIs({"dc=0", "cs=0", "W:AF", "cs=1"}), "OLED command framing still works");
    }
    // ---- invalid without D/C ---------------------------------------------------------------
    {
        TestConnection bad(nullptr, nullptr, nullptr, nullptr);
        check_true(!bad.valid(), "no D/C pin makes the connection invalid");
        g_log.clear();
        bad.sendCommand(0x12);
        check_true(g_log.empty(), "invalid connection is a no-op");
    }
    // ---- SoftSPI ----------------------------------------------------------------------------
    {
        struct Sim {
            bool sck = false, mosi = false;
            std::vector<int> sampled;
            std::vector<int> misoBits;
            size_t misoIdx = 0;
            uint32_t rising = 0;
        } sim;
        class SckPin : public OutputPin { public: Sim& s; explicit SckPin(Sim& x) : s(x) {}
            void set(bool h) override { if (h && !s.sck) { s.sampled.push_back(s.mosi ? 1 : 0); s.rising++; } s.sck = h; } };
        class MosiPin : public OutputPin { public: Sim& s; explicit MosiPin(Sim& x) : s(x) {}
            void set(bool h) override { s.mosi = h; } };
        class MisoPin : public InputPin { public: Sim& s; explicit MisoPin(Sim& x) : s(x) {}
            bool onEdge(Handler, uint8_t = kFalling) override { return false; }
            void offEdge(Handler) override {}
            bool isHigh() override { return s.misoBits[s.misoIdx++] != 0; } };

        SckPin sck(sim); MosiPin mosi(sim);
        SoftSPI w(&sck, &mosi);
        const uint8_t out[] = {0xA5, 0x3C};
        w.write(out, 2);
        const int want[] = {1,0,1,0,0,1,0,1, 0,0,1,1,1,1,0,0};
        bool same = sim.sampled.size() == 16;
        for (size_t i = 0; same && i < 16; i++) same = sim.sampled[i] == want[i];
        check_true(same, "SoftSPI write: MSB-first, sampled on rising edges");
        check_true(!sim.sck, "SoftSPI idles with SCK low");
        check_true(!w.canRead(), "SoftSPI without MISO cannot read");
        uint8_t dummy;
        check_true(!w.read(&dummy, 1), "SoftSPI read without MISO returns false");

        Sim sim2;
        sim2.misoBits = {0,0,0,1,0,0,1,1, 1,1,1,1,1,1,1,1};
        SckPin sck2(sim2); MosiPin mosi2(sim2); MisoPin miso2(sim2);
        SoftSPI r(&sck2, &mosi2, &miso2);
        uint8_t got[2] = {0, 0};
        check_true(r.canRead() && r.read(got, 2) && got[0] == 0x13 && got[1] == 0xFF,
                   "SoftSPI read: MSB-first from MISO");
        check_true(sim2.rising == 16, "SoftSPI read clocks 8 edges per byte");
    }
    // ---- mock ---------------------------------------------------------------------------------
    {
        DisplayConnectionMock m(DisplayWireMode::kHalfDuplexPin);
        m.reset();
        m.sendCommand(0x04);
        m.waitBusy(false, 100);
        const uint8_t p[] = {0xFF};
        m.sendCommand(0x10, p, 1);
        const uint8_t d[] = {0x00, 0x01};
        m.sendData(d, 2);
        const uint8_t q[] = {0x13};
        m.queueRead(q, 1);
        uint8_t buf[2] = {7, 7};
        check_true(m.readData(buf, 2) && buf[0] == 0x13 && buf[1] == 0x00, "mock reads queued bytes then zero-pads");
        const std::vector<DisplayConnectionMock::Event>& ev = m.events();
        check_true(ev.size() == 6 && ev[0].kind == DisplayConnectionMock::Event::kReset
                   && ev[1].kind == DisplayConnectionMock::Event::kCommand && ev[1].cmd == 0x04
                   && ev[2].kind == DisplayConnectionMock::Event::kWaitBusy && !ev[2].busyActiveHigh && ev[2].timeoutMs == 100
                   && ev[3].kind == DisplayConnectionMock::Event::kCommand && ev[3].bytes.size() == 1
                   && ev[4].kind == DisplayConnectionMock::Event::kData && ev[4].bytes.size() == 2
                   && ev[5].kind == DisplayConnectionMock::Event::kRead && ev[5].length == 2,
                   "mock records events in order");
        check_true(m.resetCount() == 1 && m.commandCount() == 2, "mock resetCount/commandCount");
        m.setReady(false);
        check_true(!m.waitBusy(true), "mock waitBusy honours setReady(false)");
        DisplayConnectionMock s;
        uint8_t x = 0;
        check_true(!s.readData(&x, 1), "mock readData in kSimplex returns false");
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
