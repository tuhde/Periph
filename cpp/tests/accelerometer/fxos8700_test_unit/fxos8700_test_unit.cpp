#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "FXOS8700.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tolerance, const char *label) {
    if (fabsf(got - expected) <= tolerance) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s (got %f, expected %f)\n", label, got, expected); failed++; }
}

// Data bytes of the last write to `reg` (register byte stripped); empty if none.
static std::vector<uint8_t> lastWrite(const I2CConnectionMock& mock, uint8_t reg) {
    std::vector<uint8_t> value;
    const std::vector<std::vector<uint8_t>>& log = mock.writes();
    for (size_t i = 0; i < log.size(); i++) {
        if (log[i].size() >= 2 && log[i][0] == reg) value.assign(log[i].begin() + 1, log[i].end());
    }
    return value;
}

static uint8_t lastByte(const I2CConnectionMock& mock, uint8_t reg) {
    std::vector<uint8_t> v = lastWrite(mock, reg);
    return v.empty() ? 0xEE : v[0];
}

static bool equals(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

static bool readFrom(const I2CConnectionMock& mock, uint8_t reg) {
    const std::vector<std::vector<uint8_t>>& log = mock.writes();
    for (size_t i = 0; i < log.size(); i++) {
        if (log[i].size() == 1 && log[i][0] == reg) return true;
    }
    return false;
}

// Mock that clears M_CTRL_REG1.m_ost after it is read, like the hardware.
class OneShotMock : public I2CConnectionMock {
protected:
    void _write_read(const uint8_t* data, size_t dl, uint8_t* buf, size_t bl) override {
        I2CConnectionMock::_write_read(data, dl, buf, bl);
        if (data[0] == 0x5B) {
            std::map<uint16_t, uint8_t>::const_iterator it = registers().find(0x5B);
            uint8_t v = it != registers().end() ? it->second : 0;
            setRegister(0x5B, {(uint8_t)(v & ~0x20)});
        }
    }
};

// Mock returning shifted accelerometer data while CTRL_REG2.st is set.
class SelfTestMock : public I2CConnectionMock {
public:
    int16_t delta[3] = { 0, 0, 0 };
protected:
    void _write_read(const uint8_t* data, size_t dl, uint8_t* buf, size_t bl) override {
        I2CConnectionMock::_write_read(data, dl, buf, bl);
        std::map<uint16_t, uint8_t>::const_iterator it = registers().find(0x2B);
        if (data[0] == 0x01 && bl == 6 && it != registers().end() && (it->second & 0x80)) {
            for (int axis = 0; axis < 3; axis++) {
                uint16_t raw = (uint16_t)(delta[axis] << 2);
                buf[2 * axis] = (uint8_t)(raw >> 8);
                buf[2 * axis + 1] = (uint8_t)(raw & 0xFF);
            }
        }
    }
};

int main() {
    // --- Initialization ---
    {
        I2CConnectionMock mock;
        mock.setRegister(0x0D, {0xC7});
        FXOS8700Full chip(mock);
        check_true(chip.found(), "init finds chip");
        std::vector<std::pair<uint8_t, uint8_t>> seq;
        for (size_t i = 0; i < mock.writes().size(); i++)
            if (mock.writes()[i].size() == 2) seq.push_back({mock.writes()[i][0], mock.writes()[i][1]});
        const uint8_t expect[7][2] = { {0x2B, 0x40}, {0x2A, 0x00}, {0x5B, 0x1F}, {0x5C, 0x20}, {0x0E, 0x00}, {0x2B, 0x00}, {0x2A, 0x11} };
        bool same = seq.size() == 7;
        for (size_t i = 0; same && i < 7; i++) same = seq[i].first == expect[i][0] && seq[i].second == expect[i][1];
        check_true(same, "init sequence");
    }
    {
        I2CConnectionMock mock;
        mock.setRegister(0x0D, {0xC4});
        check_true(FXOS8700Minimal(mock).found(), "pre-production WHO_AM_I accepted");
        I2CConnectionMock bad;
        bad.setRegister(0x0D, {0x1A});
        FXOS8700Minimal chip(bad);
        check_true(!chip.found(), "wrong WHO_AM_I rejected");
        check_true(lastWrite(bad, 0x2A).empty(), "nothing configured after wrong WHO_AM_I");
        I2CConnectionMock nack;
        nack.setRegister(0x0D, {0xC7});
        nack.failNextWrites(1, 1);  // skip the first WHO_AM_I read, fail the reset write
        check_true(FXOS8700Minimal(nack).found(), "NACK on reset write is tolerated");
    }

    // --- Measurements ---
    I2CConnectionMock mock;
    mock.setRegister(0x0D, {0xC7});
    FXOS8700Full chip(mock);
    mock.setRegister(0x01, {0x40, 0x00, 0xC0, 0x00, 0x00, 0x04});
    mock.setRegister(0x07, {0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4});
    mock.setRegister(0x33, {0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4});
    float x, y, z, mx, my, mz;
    chip.readAccel(x, y, z);
    check_close(x, 1.0f, 1e-5f, "accel x = +1 g");
    check_close(y, -1.0f, 1e-5f, "accel y = -1 g");
    check_close(z, 1 / 4096.0f, 1e-6f, "accel z = 1/4096 g");
    int16_t rx, ry, rz;
    chip.readRawAccel(rx, ry, rz);
    check_true(rx == 4096 && ry == -4096 && rz == 1, "raw accel");
    chip.readMag(x, y, z);
    check_close(x, 10.0f, 1e-4f, "mag x");
    check_close(y, -10.0f, 1e-4f, "mag y");
    check_close(z, 50.0f, 1e-4f, "mag z");
    chip.readRawMag(rx, ry, rz);
    check_true(rx == 100 && ry == -100 && rz == 500, "raw mag");
    float ax, ay, az;
    check_true(chip.readHybrid(ax, ay, az, mx, my, mz), "hybrid read ok");
    check_close(ax, 1.0f, 1e-5f, "hybrid accel x");
    check_close(mz, 50.0f, 1e-4f, "hybrid mag z");
    mock.setRegister(0x0B, {0x00});
    chip.setRange(4);
    chip.readAccel(x, y, z);
    check_close(x, 2.0f, 1e-5f, "4 g sensitivity");
    chip.setRange(8);
    chip.readAccel(x, y, z);
    check_close(x, 4.0f, 1e-5f, "8 g sensitivity");
    check_true(lastByte(mock, 0x0E) == 0x02, "XYZ_DATA_CFG fs = 8 g");
    check_true(!chip.setRange(3), "range 3 invalid");
    check_true(!chip.setLowNoise(true), "low-noise at 8 g rejected");
    mock.setRegister(0x51, {0xFB});
    float temp;
    check_true(chip.readTemperature(temp), "temperature ok");
    check_close(temp, -5 * 0.96f, 1e-4f, "temperature value");
    chip.setMode(0);
    check_true(!chip.readTemperature(temp), "temperature rejected in accel-only");
    check_true(!chip.readHybrid(ax, ay, az, mx, my, mz), "hybrid rejected in accel-only");
    chip.setMode(2);

    // --- Standby handling ---
    {
        I2CConnectionMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        size_t before = m.writes().size();
        c.setDataRate(100);
        std::vector<uint8_t> w;
        for (size_t i = before; i < m.writes().size(); i++)
            if (m.writes()[i].size() == 2 && m.writes()[i][0] == 0x2A) w.push_back(m.writes()[i][1]);
        check_true(w == std::vector<uint8_t>({0x10, 0x18, 0x19}), "data rate goes through STANDBY");
        check_true(!c.setDataRate(77), "rate 77 invalid");
        c.setDataRate(1.56f);
        check_true(((lastByte(m, 0x2A) >> 3) & 7) == 7, "1.56 Hz selects dr = 7");
    }
    {
        I2CConnectionMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        c.setLowNoise(true);
        check_true((lastByte(m, 0x2A) & 0x05) == 0x05, "lnoise bit");
        c.setResolution(8);
        check_true((lastByte(m, 0x2A) & 0x02) == 0x02, "f_read bit");
        m.setRegister(0x01, {0x40});
        m.setRegister(0x03, {0xC0});
        m.setRegister(0x05, {0x20});
        c.readAccel(x, y, z);
        check_close(x, 1.0f, 1e-5f, "8-bit accel x = 1 g");
        c.setResolution(14);
        c.setAccelOversampling(2, 3);
        check_true((lastByte(m, 0x2B) & 0x1B) == 0x1A, "mods / smods");
        c.setMagOversampling(5, 2);
        check_true((lastByte(m, 0x5B) & 0x1C) == (5 << 2), "m_os");
        check_true((lastByte(m, 0x5D) & 0x70) == (2 << 4), "m_aslp_os");
        c.setHighPassOutput(true, 2);
        check_true((lastByte(m, 0x0E) & 0x10) == 0x10, "hpf_out");
        check_true((lastByte(m, 0x0F) & 0x03) == 2, "hp sel");

        // --- Offsets ---
        c.setAccelOffset(0.06f, -0.06f, 1.0f);
        check_true(equals(lastWrite(m, 0x2F), {30, 0xE2, 127}), "accel offset registers");
        float ox, oy, oz;
        c.getAccelOffset(ox, oy, oz);
        check_close(ox, 0.06f, 1e-5f, "accel offset x");
        check_close(oz, 0.254f, 1e-5f, "accel offset z clamped");
        c.setMagOffset(60.0f, -60.0f, 0.0f);
        check_true(equals(lastWrite(m, 0x3F), {0x04, 0xB0, 0xFB, 0x50, 0x00, 0x00}), "mag offset registers");
        c.getMagOffset(ox, oy, oz);
        check_close(ox, 60.0f, 1e-3f, "mag offset x");
        check_close(oy, -60.0f, 1e-3f, "mag offset y");
    }

    // --- Magnetometer helpers ---
    {
        OneShotMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        c.setMagAutoCalibration(true);
        check_true((lastByte(m, 0x5B) & 0x80) == 0x80, "m_acal set");
        c.setMagAutoCalibration(false);
        check_true((lastByte(m, 0x5B) & 0x80) == 0, "m_acal cleared");
        c.resetMagMinMax();
        check_true((lastByte(m, 0x5C) & 0x04) == 0x04, "m_maxmin_rst");
        c.triggerMagReset();
        check_true((lastByte(m, 0x5B) & 0x40) == 0x40, "m_rst");
        c.setMagReset(2);
        check_true((lastByte(m, 0x5C) & 0x03) == 2, "m_rst_cnt");
        m.setRegister(0x4B, {0xFF, 0x9C});
        m.setRegister(0x45, {0x00, 0x64});
        float mn[3], mxv[3];
        c.readMagMinMax(mn, mxv);
        check_close(mn[0], -10.0f, 1e-4f, "min x");
        check_close(mxv[0], 10.0f, 1e-4f, "max x");
        check_true(c.measureMagOnce(x, y, z), "one-shot measurement");
    }

    // --- Heading ---
    {
        I2CConnectionMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        c.setMode(0);
        m.setRegister(0x01, {0, 0, 0, 0, 0x40, 0});
        m.setRegister(0x33, {0x01, 0xF4, 0, 0, 0, 0});
        check_close(c.heading(), 0.0f, 1e-3f, "heading north");
        check_close(c.heading(10.0f), 10.0f, 1e-3f, "heading with declination");
        m.setRegister(0x33, {0x00, 0x00, 0xFE, 0x0C, 0, 0});
        check_close(c.heading(), 90.0f, 1e-3f, "heading east");
    }

    // --- FIFO ---
    {
        I2CConnectionMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        m.setRegister(0x00, {0x42});
        m.setRegister(0x01, {0x40});
        c.setFifo(1, 16);
        check_true(lastByte(m, 0x09) == 0x50, "F_SETUP circular wm 16");
        size_t before = m.writes().size();
        c.setFifo(2, 8);
        std::vector<uint8_t> f;
        for (size_t i = before; i < m.writes().size(); i++)
            if (m.writes()[i].size() == 2 && m.writes()[i][0] == 0x09) f.push_back(m.writes()[i][1]);
        check_true(f == std::vector<uint8_t>({0x00, 0x88}), "mode change goes through disabled");
        check_true(c.fifoLevel() == 2, "fifo level");
        bool ovf, wm;
        uint8_t cnt;
        c.readFifoStatus(ovf, wm, cnt);
        check_true(!ovf && wm && cnt == 2, "fifo status");
        float samples[6];
        check_true(c.readFifo(samples, 2) == 2, "read_fifo count");
        check_close(samples[0], 1.0f, 1e-5f, "fifo sample x");
        check_true(!c.setResolution(8), "resolution with FIFO rejected");
        c.setFifoTrigger(true, false, false, true);
        check_true(lastByte(m, 0x0A) == 0x24, "TRIG_CFG");
        check_true(!c.setFifo(4), "bad fifo mode rejected");
    }

    // --- Detectors ---
    {
        I2CConnectionMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        c.setFreefallMotion(true, 7, 0.3f, 60.0f, true, false);
        check_true(lastByte(m, 0x15) == 0xF8, "A_FFMT_CFG");
        check_true(lastByte(m, 0x17) == 5, "A_FFMT_THS");
        check_true(lastByte(m, 0x18) == 6, "A_FFMT_COUNT");
        c.setFreefallMotion(false, 3, 0.2f, 0.0f, false, true);
        check_true(lastByte(m, 0x15) == 0x18, "freefall CFG");
        check_true(lastByte(m, 0x17) == 0x83, "freefall THS dbcntm");
        c.setTransient(6, 0.5f, 40.0f, true, true);
        check_true(lastByte(m, 0x1D) == 0x1D, "TRANSIENT_CFG");
        check_true(lastByte(m, 0x1F) == 8, "TRANSIENT_THS");
        check_true(lastByte(m, 0x20) == 4, "TRANSIENT_COUNT");
        const float th[3] = { 0.5f, 0.5f, 1.0f };
        c.setPulse(1, 4, th, 30.0f, 100.0f, 300.0f, true);
        check_true(lastByte(m, 0x21) == 0x61, "PULSE_CFG");
        check_true(equals(lastWrite(m, 0x23), {8, 8, 16}), "pulse thresholds");
        check_true(lastByte(m, 0x26) == 3, "PULSE_TMLT");
        check_true(lastByte(m, 0x27) == 5, "PULSE_LTCY");
        check_true(lastByte(m, 0x28) == 15, "PULSE_WIND");
        check_true((lastByte(m, 0x0F) & 0x30) == 0x10, "pulse filter bits");
        c.setPulse(1, 0, nullptr, 30.0f, 100.0f, 300.0f, false, false, false, false);
        check_true(lastByte(m, 0x26) == 12, "pulse no-LPF TMLT");
        check_true((lastByte(m, 0x0F) & 0x30) == 0x20, "pulse bypass HPF");
        check_true(c.setOrientation(true, 100.0f), "orientation ok");
        check_true(lastByte(m, 0x11) == 0xC0, "PL_CFG");
        check_true(lastByte(m, 0x12) == 10, "PL_COUNT");
        check_true(lastByte(m, 0x14) == 0x84, "PL_THS_REG");
        check_true(lastByte(m, 0x13) == 0x84, "PL_BF_ZCOMP");
        check_true(!c.setOrientation(true, 0.0f, 75, 7), "orientation overflow rejected");
        check_true(!c.setOrientation(true, 0.0f, 50), "bad angle rejected");
        m.setRegister(0x10, {(uint8_t)(0x80 | 0x40 | (3 << 1) | 1)});
        bool changed, lockout, back;
        uint8_t pl;
        c.readOrientation(changed, lockout, pl, back);
        check_true(changed && lockout && pl == 3 && back, "read_orientation");
        check_true(c.setAutoSleep(true, 12.5f, 10.0f, FXOS8700Full::SOURCE_FF_MT | FXOS8700Full::SOURCE_PULSE), "auto-sleep ok");
        check_true((lastByte(m, 0x2A) >> 6) == 1, "aslp rate");
        check_true(lastByte(m, 0x29) == 31, "ASLP_COUNT");
        check_true((lastByte(m, 0x2B) & 0x04) == 0x04, "slpe");
        check_true((lastByte(m, 0x2C) & 0x7C) == 0x18, "wake bits");
        check_true(!c.setAutoSleep(true, 50.0f, 10.0f, 0x01), "bad wake source rejected");
        c.setFifoGate(true);
        check_true((lastByte(m, 0x2C) & 0x80) == 0x80, "fifo gate");
        c.setInterruptPins(true, true);
        check_true((lastByte(m, 0x2C) & 0x03) == 0x03, "ipol / pp_od");
    }

    // --- Interrupts ---
    {
        I2CConnectionMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        c.enableInterrupt(FXOS8700Full::SOURCE_FF_MT | FXOS8700Full::SOURCE_ORIENTATION, 1);
        check_true(lastByte(m, 0x2D) == 0x14, "CTRL_REG4 enabled");
        check_true(lastByte(m, 0x2E) == 0x14, "CTRL_REG5 routed INT1");
        c.enableInterrupt(FXOS8700Full::SOURCE_FF_MT, 2);
        check_true(lastByte(m, 0x2E) == 0x10, "CTRL_REG5 routed INT2");
        c.disableInterrupt(FXOS8700Full::SOURCE_ORIENTATION);
        check_true(lastByte(m, 0x2D) == 0x04, "CTRL_REG4 disabled");
        check_true(!c.enableInterrupt(1, 3), "bad pin rejected");
        m.setRegister(0x0C, {0x14});
        check_true(c.pollInterrupt() == 0x14, "poll_interrupt");
        check_true(readFrom(m, 0x16) && readFrom(m, 0x10), "poll reads source registers");
    }

    // --- Misc ---
    {
        I2CConnectionMock m;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        const uint8_t data[2] = { 1, 2 };
        c.writeRegister(0x5F, data, 2);
        check_true(equals(lastWrite(m, 0x5F), {1, 2}), "write_register");
        uint8_t back[2] = { 0, 0 };
        c.readRegister(0x5F, back, 2);
        check_true(back[0] == 1 && back[1] == 2, "read_register");
        m.setRegister(0x00, {0x08});
        check_true(c.newAccelData(), "new_accel_data");
        check_true(!c.newMagData(), "new_mag_data false");
        m.setRegister(0x0B, {0x02});
        check_true(c.readSystemMode() == 2, "system mode");
        m.setRegister(0x0B, {0x00});
        c.reset();
        check_true(!c.readHybrid(ax, ay, az, mx, my, mz), "reset disables hybrid read");
        check_true(c.init() && c.readHybrid(ax, ay, az, mx, my, mz), "init restores hybrid");
    }

    // --- Self-test ---
    {
        SelfTestMock m;
        m.delta[0] = 200; m.delta[1] = 300; m.delta[2] = 1300;
        m.setRegister(0x0D, {0xC7});
        FXOS8700Full c(m);
        int16_t dx, dy, dz;
        check_true(c.selfTest(dx, dy, dz), "self-test passes");
        check_true(dx == 200 && dy == 300 && dz == 1300, "self-test deltas");
        check_true(lastByte(m, 0x2A) == 0x11, "self-test restores CTRL_REG1");
        SelfTestMock weak;
        weak.delta[0] = weak.delta[1] = weak.delta[2] = 10;
        weak.setRegister(0x0D, {0xC7});
        FXOS8700Full w(weak);
        check_true(!w.selfTest(dx, dy, dz), "self-test fails on weak response");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
