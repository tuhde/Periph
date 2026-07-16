#include "Rda5807m.h"

#ifdef __linux__
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

#ifdef CONFIG_ZEPHYR
#include <zephyr/kernel.h>
#undef DELAY_MS
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
#endif

static const uint32_t BAND_BASE_KHZ[4] = {87000, 76000, 76000, 65000};
static const uint16_t SPACE_KHZ[4] = {100, 200, 50, 25};

static const uint16_t STC_TIMEOUT_MS = 500;
static const uint16_t STC_POLL_MS = 1;

uint16_t RDA5807MMinimal::_freq_to_chan(uint8_t band, uint8_t space, bool east_europe_50m, float frequency_mhz) {
    uint32_t base = (band == 3 && east_europe_50m) ? 50000 : BAND_BASE_KHZ[band];
    int32_t freq_khz = static_cast<int32_t>(frequency_mhz * 1000.0f + 0.5f);
    int32_t chan = (freq_khz - static_cast<int32_t>(base)) / SPACE_KHZ[space];
    if (chan < 0) chan = 0;
    if (chan > 1023) chan = 1023;
    return static_cast<uint16_t>(chan);
}

float RDA5807MMinimal::_chan_to_freq(uint8_t band, uint8_t space, bool east_europe_50m, uint16_t chan) {
    uint32_t base = (band == 3 && east_europe_50m) ? 50000 : BAND_BASE_KHZ[band];
    return (base + static_cast<uint32_t>(chan) * SPACE_KHZ[space]) / 1000.0f;
}

RDA5807MMinimal::RDA5807MMinimal(Transport& transport, float frequency_mhz, uint8_t volume)
    : _transport(transport), _band(BAND_WORLD), _space(SPACE_100K), _east_europe_50m(false) {
    uint16_t ctrl = _DHIZ | _DMUTE | _SKMODE | _NEW_METHOD | _ENABLE;
    uint16_t chan = _freq_to_chan(_band, _space, _east_europe_50m, frequency_mhz);
    uint16_t chan_reg = (chan << 6) | _TUNE | (_band << 2) | _space;
    uint16_t r4 = _SOFTMUTE_EN | _DE;
    uint16_t r5 = _INT_MODE | (8 << 8) | (volume & 0x0F);
    uint16_t r6 = 0x0000;
    uint16_t r7 = (16 << 10) | _BAND_65M_50M | 0x0002;

    _regs[0] = ctrl;
    _regs[1] = chan_reg;
    _regs[2] = r4;
    _regs[3] = r5;
    _regs[4] = r6;
    _regs[5] = r7;

    _write_regs();
    _wait_stc();
    _regs[1] &= ~_TUNE;
}

void RDA5807MMinimal::_write_regs() {
    uint8_t buf[12];
    for (uint8_t i = 0; i < 6; i++) {
        buf[i * 2] = static_cast<uint8_t>(_regs[i] >> 8);
        buf[i * 2 + 1] = static_cast<uint8_t>(_regs[i] & 0xFF);
    }
    _transport.write(buf, 12);
}

void RDA5807MMinimal::_read_status(uint16_t* words, uint8_t count) {
    uint8_t buf[12];
    _transport.read(buf, count * 2);
    for (uint8_t i = 0; i < count; i++) {
        words[i] = (static_cast<uint16_t>(buf[i * 2]) << 8) | buf[i * 2 + 1];
    }
}

uint16_t RDA5807MMinimal::_wait_stc() {
    uint16_t elapsed = 0;
    uint16_t status_a[1];
    while (elapsed < STC_TIMEOUT_MS) {
        _read_status(status_a, 1);
        if (status_a[0] & _STC) return status_a[0];
        DELAY_MS(STC_POLL_MS);
        elapsed += STC_POLL_MS;
    }
    return 0;
}

void RDA5807MMinimal::set_frequency(float frequency_mhz) {
    uint16_t chan = _freq_to_chan(_band, _space, _east_europe_50m, frequency_mhz);
    _regs[1] = (chan << 6) | _TUNE | (_band << 2) | _space;
    _write_regs();
    _wait_stc();
    _regs[1] &= ~_TUNE;
}

float RDA5807MMinimal::frequency() {
    uint16_t status_a[1];
    _read_status(status_a, 1);
    uint16_t readchan = status_a[0] & 0x03FF;
    return _chan_to_freq(_band, _space, _east_europe_50m, readchan);
}

void RDA5807MMinimal::set_volume(uint8_t level) {
    _regs[3] = (_regs[3] & ~0x000F) | (level & 0x0F);
    _write_regs();
}

void RDA5807MMinimal::mute(bool enable) {
    if (enable) {
        _regs[0] &= ~_DMUTE;
    } else {
        _regs[0] |= _DMUTE;
    }
    _write_regs();
}

bool RDA5807MMinimal::seek(bool up, float& frequency_mhz) {
    if (up) {
        _regs[0] |= _SEEKUP;
    } else {
        _regs[0] &= ~_SEEKUP;
    }
    _regs[0] |= _SEEK;
    _write_regs();
    uint16_t status_a = _wait_stc();
    _regs[0] &= ~_SEEK;
    _write_regs();

    if (status_a & _SF) return false;
    uint16_t readchan = status_a & 0x03FF;
    frequency_mhz = _chan_to_freq(_band, _space, _east_europe_50m, readchan);
    return true;
}

RDA5807MFull::RDA5807MFull(Transport& transport, float frequency_mhz, uint8_t volume)
    : RDA5807MMinimal(transport, frequency_mhz, volume) {}

void RDA5807MFull::configure(uint8_t band, uint8_t space, int8_t de_emphasis,
                              int16_t seek_threshold, int8_t seek_mode, int16_t clk_mode,
                              int8_t afc_disable, int8_t east_europe_50m) {
    bool retune = false;
    float current_freq = frequency();

    if (band != 0xFF && band != _band) {
        _band = band;
        retune = true;
    }
    if (space != 0xFF && space != _space) {
        _space = space;
        retune = true;
    }
    if (east_europe_50m >= 0 && (east_europe_50m != 0) != _east_europe_50m) {
        _east_europe_50m = (east_europe_50m != 0);
        retune = true;
    }

    _regs[1] = (_regs[1] & ~0x000F) | (_band << 2) | _space;

    if (de_emphasis >= 0) {
        if (de_emphasis) _regs[2] |= _DE;
        else _regs[2] &= ~_DE;
    }
    if (afc_disable >= 0) {
        if (afc_disable) _regs[2] |= _AFCD;
        else _regs[2] &= ~_AFCD;
    }
    if (seek_threshold >= 0) {
        _regs[3] = (_regs[3] & ~0x0F00) | ((static_cast<uint16_t>(seek_threshold) & 0x0F) << 8);
    }
    if (seek_mode >= 0) {
        if (seek_mode) _regs[0] |= _SKMODE;
        else _regs[0] &= ~_SKMODE;
    }
    if (clk_mode >= 0) {
        _regs[0] = (_regs[0] & ~0x0070) | ((static_cast<uint16_t>(clk_mode) & 0x07) << 4);
    }
    if (east_europe_50m >= 0) {
        if (_east_europe_50m) _regs[5] &= ~_BAND_65M_50M;
        else _regs[5] |= _BAND_65M_50M;
    }

    if (retune) {
        set_frequency(current_freq);
    } else {
        _write_regs();
    }
}

void RDA5807MFull::set_bass_boost(bool enable) {
    if (enable) _regs[0] |= _BASS;
    else _regs[0] &= ~_BASS;
    _write_regs();
}

void RDA5807MFull::set_mono(bool enable) {
    if (enable) _regs[0] |= _MONO;
    else _regs[0] &= ~_MONO;
    _write_regs();
}

void RDA5807MFull::set_softmute(bool enable) {
    if (enable) _regs[2] |= _SOFTMUTE_EN;
    else _regs[2] &= ~_SOFTMUTE_EN;
    _write_regs();
}

void RDA5807MFull::enable_rds(bool enable) {
    if (enable) _regs[0] |= _RDS_EN;
    else _regs[0] &= ~_RDS_EN;
    _write_regs();
}

bool RDA5807MFull::rds_ready() {
    uint16_t status_a[1];
    _read_status(status_a, 1);
    return (status_a[0] & _RDSR) != 0;
}

bool RDA5807MFull::read_rds_group(uint16_t& block_a, uint16_t& block_b, uint16_t& block_c, uint16_t& block_d) {
    uint16_t words[6];
    _read_status(words, 6);
    if (!(words[0] & _RDSR)) return false;
    block_a = words[2];
    block_b = words[3];
    block_c = words[4];
    block_d = words[5];
    return true;
}

bool RDA5807MFull::is_stereo() {
    uint16_t status_a[1];
    _read_status(status_a, 1);
    return (status_a[0] & _ST) != 0;
}

bool RDA5807MFull::is_station() {
    uint16_t words[2];
    _read_status(words, 2);
    return (words[1] & _FM_TRUE) != 0;
}

bool RDA5807MFull::is_ready() {
    uint16_t words[2];
    _read_status(words, 2);
    return (words[1] & _FM_READY) != 0;
}

uint8_t RDA5807MFull::signal_strength() {
    uint16_t words[2];
    _read_status(words, 2);
    return static_cast<uint8_t>((words[1] >> 9) & 0x7F);
}

void RDA5807MFull::standby(bool enable) {
    if (enable) _regs[0] &= ~_ENABLE;
    else _regs[0] |= _ENABLE;
    _write_regs();
}

void RDA5807MFull::soft_reset() {
    _regs[0] |= _SOFT_RESET;
    _write_regs();
    _regs[0] &= ~_SOFT_RESET;
    _write_regs();
}
