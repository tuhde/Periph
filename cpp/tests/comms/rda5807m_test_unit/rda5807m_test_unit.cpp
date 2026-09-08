#include <cstdio>
#include <cstdint>
#include <vector>
#include <algorithm>
#include "I2CConnectionMock.h"
#include "RDA5807M.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Register bit constants, mirrored from cpp/src/chips/comms/RDA5807M.h/.cpp
// (protected there, so re-declared here to build expected values).
static const uint32_t BAND_BASE_KHZ[4] = {87000, 76000, 76000, 65000};
static const uint16_t SPACE_KHZ[4] = {100, 200, 50, 25};

static const uint8_t BAND_WORLD = 2;
static const uint8_t SPACE_100K = 0;
static const uint8_t BAND_US_EUROPE = 0;
static const uint8_t SPACE_50K = 2;

static const uint16_t DHIZ = 0x8000;
static const uint16_t DMUTE = 0x4000;
static const uint16_t MONO = 0x2000;
static const uint16_t BASS = 0x1000;
static const uint16_t SEEKUP = 0x0200;
static const uint16_t SEEK = 0x0100;
static const uint16_t SKMODE = 0x0080;
static const uint16_t RDS_EN = 0x0008;
static const uint16_t NEW_METHOD = 0x0004;
static const uint16_t SOFT_RESET = 0x0002;
static const uint16_t ENABLE = 0x0001;
static const uint16_t TUNE = 0x0010;
static const uint16_t DE = 0x0800;
static const uint16_t SOFTMUTE_EN = 0x0200;
static const uint16_t AFCD = 0x0100;
static const uint16_t INT_MODE = 0x8000;
static const uint16_t BAND_65M_50M = 0x0200;
static const uint16_t RDSR = 0x8000;
static const uint16_t STC = 0x4000;
static const uint16_t SF = 0x2000;
static const uint16_t ST = 0x0400;
static const uint16_t FM_TRUE = 0x0100;
static const uint16_t FM_READY = 0x0080;

static uint16_t freq_to_chan(uint8_t band, uint8_t space, bool east50, float freq_mhz) {
    uint32_t base = (band == 3 && east50) ? 50000 : BAND_BASE_KHZ[band];
    int32_t freq_khz = static_cast<int32_t>(freq_mhz * 1000.0f + 0.5f);
    int32_t chan = (freq_khz - static_cast<int32_t>(base)) / SPACE_KHZ[space];
    if (chan < 0) chan = 0;
    if (chan > 1023) chan = 1023;
    return static_cast<uint16_t>(chan);
}

static float chan_to_freq(uint8_t band, uint8_t space, bool east50, uint16_t chan) {
    uint32_t base = (band == 3 && east50) ? 50000 : BAND_BASE_KHZ[band];
    return (base + static_cast<uint32_t>(chan) * SPACE_KHZ[space]) / 1000.0f;
}

static std::vector<uint8_t> regsBytes(const uint16_t regs[6]) {
    std::vector<uint8_t> buf(12);
    for (int i = 0; i < 6; i++) {
        buf[i * 2] = static_cast<uint8_t>(regs[i] >> 8);
        buf[i * 2 + 1] = static_cast<uint8_t>(regs[i] & 0xFF);
    }
    return buf;
}

static std::vector<uint8_t> statusBytes(std::initializer_list<uint16_t> words) {
    std::vector<uint8_t> buf;
    for (uint16_t w : words) {
        buf.push_back(static_cast<uint8_t>(w >> 8));
        buf.push_back(static_cast<uint8_t>(w & 0xFF));
    }
    return buf;
}

struct Fixture {
    I2CConnectionMock *connection;
    RDA5807MFull *sensor;
    uint16_t regs[6];
    uint8_t band, space;
    bool east50;
};

// Construct a fresh RDA5807MFull with a queued STC-set status so the
// blocking waitStc() inside the constructor resolves on its first poll, and
// fill in `out` with the expected post-init shadow register array (TUNE
// already cleared, mirroring what the driver does once it observes STC).
static void newSensor(Fixture &out, float frequency_mhz = 100.0f, uint8_t volume = 8) {
    out.connection = new I2CConnectionMock();
    auto stc = statusBytes({STC});
    out.connection->queueRead({stc[0], stc[1]});
    out.band = BAND_WORLD;
    out.space = SPACE_100K;
    out.east50 = false;
    uint16_t chan0 = freq_to_chan(out.band, out.space, out.east50, frequency_mhz);
    out.regs[0] = DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE;
    out.regs[1] = (chan0 << 6) | TUNE | (out.band << 2) | out.space;
    out.regs[2] = SOFTMUTE_EN | DE;
    out.regs[3] = INT_MODE | (8 << 8) | (volume & 0x0F);
    out.regs[4] = 0x0000;
    out.regs[5] = (16 << 10) | BAND_65M_50M | 0x0002;
    out.sensor = new RDA5807MFull(*out.connection, frequency_mhz, volume);
    out.regs[1] &= static_cast<uint16_t>(~TUNE);
}

static bool bytesEq(const std::vector<uint8_t> &a, const std::vector<uint8_t> &b) {
    return a == b;
}

int main() {
    // --- init ---
    {
        Fixture f;
        newSensor(f);
        uint16_t expected[6];
        for (int i = 0; i < 6; i++) expected[i] = f.regs[i];
        expected[1] |= TUNE;
        check_true(bytesEq(f.connection->writes()[0], regsBytes(expected)), "init_writes_regs");
    }

    // --- frequency() ---
    {
        Fixture f;
        newSensor(f);
        auto sb = statusBytes({250});
        f.connection->queueRead({sb[0], sb[1]});
        float freq = f.sensor->frequency();
        check_true(freq == chan_to_freq(f.band, f.space, f.east50, 250), "frequency");
    }

    // --- set_frequency() ---
    {
        Fixture f;
        newSensor(f);
        auto sb = statusBytes({STC});
        f.connection->queueRead({sb[0], sb[1]});
        f.sensor->set_frequency(103.5f);
        uint16_t chan1 = freq_to_chan(f.band, f.space, f.east50, 103.5f);
        f.regs[1] = (chan1 << 6) | TUNE | (f.band << 2) | f.space;
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "set_frequency_writes");
    }

    // --- set_volume() ---
    {
        Fixture f;
        newSensor(f);
        f.sensor->set_volume(5);
        f.regs[3] = (f.regs[3] & ~0x000F) | (5 & 0x0F);
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "set_volume");
    }

    // --- mute() ---
    {
        Fixture f;
        newSensor(f);
        f.sensor->mute(true);
        f.regs[0] &= static_cast<uint16_t>(~DMUTE);
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "mute_true");
        f.sensor->mute(false);
        f.regs[0] |= DMUTE;
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "mute_false");
    }

    // --- seek() found ---
    {
        Fixture f;
        newSensor(f);
        auto sb = statusBytes({static_cast<uint16_t>(STC | 300)});
        f.connection->queueRead({sb[0], sb[1]});
        float result = 0.0f;
        bool ok = f.sensor->seek(true, result);
        f.regs[0] |= SEEKUP;
        f.regs[0] |= SEEK;
        auto firstWrite = regsBytes(f.regs);
        f.regs[0] &= static_cast<uint16_t>(~SEEK);
        auto secondWrite = regsBytes(f.regs);
        const auto &writes = f.connection->writes();
        size_t n = writes.size();
        check_true(bytesEq(writes[n - 2], firstWrite) && bytesEq(writes[n - 1], secondWrite), "seek_up_writes");
        check_true(ok && result == chan_to_freq(f.band, f.space, f.east50, 300), "seek_up_result");
    }

    // --- seek() fails (SF set) ---
    {
        Fixture f;
        newSensor(f);
        auto sb = statusBytes({static_cast<uint16_t>(STC | SF)});
        f.connection->queueRead({sb[0], sb[1]});
        float result = 0.0f;
        bool ok = f.sensor->seek(false, result);
        check_true(!ok, "seek_fail_returns_false");
    }

    // --- configure() with retune (band/space change) ---
    {
        Fixture f;
        newSensor(f);
        auto freqSb = statusBytes({500});
        f.connection->queueRead({freqSb[0], freqSb[1]});  // configure() reads current frequency() first
        float currentFreq = chan_to_freq(f.band, f.space, f.east50, 500);
        auto stcSb = statusBytes({STC});
        f.connection->queueRead({stcSb[0], stcSb[1]});  // for the resulting retune's waitStc
        f.sensor->configure(BAND_US_EUROPE, SPACE_50K, 0 /*de_emphasis=false*/, 10, 0 /*seek_mode=false*/, 3, 1 /*afc_disable=true*/);
        f.band = BAND_US_EUROPE;
        f.space = SPACE_50K;
        f.regs[2] &= static_cast<uint16_t>(~DE);
        f.regs[2] |= AFCD;
        f.regs[3] = (f.regs[3] & ~0x0F00) | ((10 & 0x0F) << 8);
        f.regs[0] &= static_cast<uint16_t>(~SKMODE);
        f.regs[0] = (f.regs[0] & ~0x0070) | ((3 & 0x07) << 4);
        uint16_t chan2 = freq_to_chan(f.band, f.space, f.east50, currentFreq);
        f.regs[1] = (chan2 << 6) | TUNE | (f.band << 2) | f.space;
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "configure_retunes");
    }

    // --- configure() without retune (band/space unchanged) ---
    {
        Fixture f;
        newSensor(f);
        auto freqSb = statusBytes({0});
        f.connection->queueRead({freqSb[0], freqSb[1]});  // configure() still reads frequency() first
        f.sensor->configure(0xFF, 0xFF, -1, 4, -1, -1, -1);
        f.regs[3] = (f.regs[3] & ~0x0F00) | ((4 & 0x0F) << 8);
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "configure_no_retune");
    }

    // --- set_bass_boost / set_mono / set_softmute / enable_rds (chained: no reads involved) ---
    {
        Fixture f;
        newSensor(f);
        f.sensor->set_bass_boost(true);
        f.regs[0] |= BASS;
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "set_bass_boost");

        f.sensor->set_mono(true);
        f.regs[0] |= MONO;
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "set_mono");

        f.sensor->set_softmute(false);
        f.regs[2] &= static_cast<uint16_t>(~SOFTMUTE_EN);
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "set_softmute");

        f.sensor->enable_rds(true);
        f.regs[0] |= RDS_EN;
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "enable_rds");
    }

    // --- rds_ready() ---
    {
        Fixture f;
        newSensor(f);
        auto sb1 = statusBytes({RDSR});
        f.connection->queueRead({sb1[0], sb1[1]});
        check_true(f.sensor->rds_ready(), "rds_ready_true");
        auto sb2 = statusBytes({0});
        f.connection->queueRead({sb2[0], sb2[1]});
        check_true(!f.sensor->rds_ready(), "rds_ready_false");
    }

    // --- read_rds_group() ---
    {
        Fixture f;
        newSensor(f);
        auto sb = statusBytes({RDSR, 0, 0x1122, 0x3344, 0x5566, 0x7788});
        f.connection->queueRead({sb[0], sb[1], sb[2], sb[3], sb[4], sb[5],
                                  sb[6], sb[7], sb[8], sb[9], sb[10], sb[11]});
        uint16_t a, b, c, d;
        bool ok = f.sensor->read_rds_group(a, b, c, d);
        check_true(ok && a == 0x1122 && b == 0x3344 && c == 0x5566 && d == 0x7788, "read_rds_group");

        auto sbNone = statusBytes({0, 0, 0, 0, 0, 0});
        f.connection->queueRead({sbNone[0], sbNone[1], sbNone[2], sbNone[3], sbNone[4], sbNone[5],
                                  sbNone[6], sbNone[7], sbNone[8], sbNone[9], sbNone[10], sbNone[11]});
        check_true(!f.sensor->read_rds_group(a, b, c, d), "read_rds_group_none");
    }

    // --- is_stereo / is_station / is_ready / signal_strength ---
    {
        Fixture f;
        newSensor(f);
        auto sb1 = statusBytes({ST});
        f.connection->queueRead({sb1[0], sb1[1]});
        check_true(f.sensor->is_stereo(), "is_stereo_true");

        auto sb2 = statusBytes({0, FM_TRUE});
        f.connection->queueRead({sb2[0], sb2[1], sb2[2], sb2[3]});
        check_true(f.sensor->is_station(), "is_station_true");

        auto sb3 = statusBytes({0, FM_READY});
        f.connection->queueRead({sb3[0], sb3[1], sb3[2], sb3[3]});
        check_true(f.sensor->is_ready(), "is_ready_true");

        auto sb4 = statusBytes({0, static_cast<uint16_t>((100 << 9) & 0xFFFF)});
        f.connection->queueRead({sb4[0], sb4[1], sb4[2], sb4[3]});
        check_true(f.sensor->signal_strength() == 100, "signal_strength");
    }

    // --- standby() ---
    {
        Fixture f;
        newSensor(f);
        f.sensor->standby(true);
        f.regs[0] &= static_cast<uint16_t>(~ENABLE);
        check_true(bytesEq(f.connection->writes().back(), regsBytes(f.regs)), "standby_down");

        auto sb = statusBytes({STC});
        f.connection->queueRead({sb[0], sb[1]});  // standby(false)'s internal set_frequency's waitStc
        f.sensor->standby(false);
        f.regs[0] |= ENABLE;
        auto enableWrite = regsBytes(f.regs);
        uint16_t chan3 = freq_to_chan(f.band, f.space, f.east50, 100.0f);  // newSensor()'s default frequency, unchanged so far
        f.regs[1] = (chan3 << 6) | TUNE | (f.band << 2) | f.space;
        auto retuneWrite = regsBytes(f.regs);
        const auto &writes = f.connection->writes();
        size_t n = writes.size();
        check_true(bytesEq(writes[n - 2], enableWrite) && bytesEq(writes[n - 1], retuneWrite), "standby_up_writes");
    }

    // --- soft_reset() ---
    {
        Fixture f;
        newSensor(f);
        auto sb = statusBytes({STC});
        f.connection->queueRead({sb[0], sb[1]});  // soft_reset()'s internal set_frequency's waitStc
        f.sensor->soft_reset();
        f.regs[0] |= SOFT_RESET;
        auto setWrite = regsBytes(f.regs);
        f.regs[0] &= static_cast<uint16_t>(~SOFT_RESET);
        auto clearWrite = regsBytes(f.regs);
        uint16_t chan4 = freq_to_chan(f.band, f.space, f.east50, 100.0f);  // newSensor()'s default frequency, unchanged so far
        f.regs[1] = (chan4 << 6) | TUNE | (f.band << 2) | f.space;
        auto retuneWrite = regsBytes(f.regs);
        const auto &writes = f.connection->writes();
        size_t n = writes.size();
        check_true(bytesEq(writes[n - 3], setWrite) && bytesEq(writes[n - 2], clearWrite) &&
                       bytesEq(writes[n - 1], retuneWrite),
                   "soft_reset_writes");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
