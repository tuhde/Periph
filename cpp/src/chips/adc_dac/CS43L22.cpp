#include "CS43L22.h"

#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members (required before C++17).
constexpr uint8_t CS43L22Minimal::I2C_ADDRESS;
constexpr uint8_t CS43L22Minimal::REG_ID;
constexpr uint8_t CS43L22Minimal::REG_POWER_CTL_1;
constexpr uint8_t CS43L22Minimal::REG_POWER_CTL_2;
constexpr uint8_t CS43L22Minimal::REG_CLOCKING_CTL;
constexpr uint8_t CS43L22Minimal::REG_INTERFACE_CTL_1;
constexpr uint8_t CS43L22Minimal::REG_INTERFACE_CTL_2;
constexpr uint8_t CS43L22Minimal::REG_PASSTHRU_A_SEL;
constexpr uint8_t CS43L22Minimal::REG_PASSTHRU_B_SEL;
constexpr uint8_t CS43L22Minimal::REG_ANALOG_ZC_SR;
constexpr uint8_t CS43L22Minimal::REG_PLAYBACK_CTL_1;
constexpr uint8_t CS43L22Minimal::REG_MISC_CTL;
constexpr uint8_t CS43L22Minimal::REG_PLAYBACK_CTL_2;
constexpr uint8_t CS43L22Minimal::REG_PASSTHRU_A_VOL;
constexpr uint8_t CS43L22Minimal::REG_PASSTHRU_B_VOL;
constexpr uint8_t CS43L22Minimal::REG_PCM_A_VOL;
constexpr uint8_t CS43L22Minimal::REG_PCM_B_VOL;
constexpr uint8_t CS43L22Minimal::REG_BEEP_FREQ_ON;
constexpr uint8_t CS43L22Minimal::REG_BEEP_VOL_OFF;
constexpr uint8_t CS43L22Minimal::REG_BEEP_TONE_CFG;
constexpr uint8_t CS43L22Minimal::REG_TONE_CTL;
constexpr uint8_t CS43L22Minimal::REG_MASTER_A_VOL;
constexpr uint8_t CS43L22Minimal::REG_MASTER_B_VOL;
constexpr uint8_t CS43L22Minimal::REG_HP_A_VOL;
constexpr uint8_t CS43L22Minimal::REG_HP_B_VOL;
constexpr uint8_t CS43L22Minimal::REG_SPK_A_VOL;
constexpr uint8_t CS43L22Minimal::REG_SPK_B_VOL;
constexpr uint8_t CS43L22Minimal::REG_PCM_MIXER_SWAP;
constexpr uint8_t CS43L22Minimal::REG_LIMIT_CTL_1;
constexpr uint8_t CS43L22Minimal::REG_LIMIT_CTL_2;
constexpr uint8_t CS43L22Minimal::REG_LIMIT_ATTACK;
constexpr uint8_t CS43L22Minimal::REG_STATUS;
constexpr uint8_t CS43L22Minimal::REG_BATTERY_COMP;
constexpr uint8_t CS43L22Minimal::REG_VP_LEVEL;
constexpr uint8_t CS43L22Minimal::REG_SPEAKER_STATUS;
constexpr uint8_t CS43L22Minimal::REG_CHARGE_PUMP_FREQ;
constexpr uint8_t CS43L22Minimal::REG_INIT_00;
constexpr uint8_t CS43L22Minimal::REG_INIT_32;
constexpr uint8_t CS43L22Minimal::REG_INIT_47;
constexpr uint8_t CS43L22Full::OUT_FOLLOW_LOW;
constexpr uint8_t CS43L22Full::OUT_FOLLOW_HIGH;
constexpr uint8_t CS43L22Full::OUT_ON;
constexpr uint8_t CS43L22Full::OUT_OFF;
constexpr uint8_t CS43L22Full::FMT_LJ;
constexpr uint8_t CS43L22Full::FMT_I2S;
constexpr uint8_t CS43L22Full::FMT_RJ;
constexpr uint8_t CS43L22Full::BEEP_OFF;
constexpr uint8_t CS43L22Full::BEEP_SINGLE;
constexpr uint8_t CS43L22Full::BEEP_MULTIPLE;
constexpr uint8_t CS43L22Full::BEEP_CONTINUOUS;
constexpr uint8_t CS43L22Full::SRC_NORMAL;
constexpr uint8_t CS43L22Full::SRC_MIX;
constexpr uint8_t CS43L22Full::SRC_SWAPPED;
constexpr uint8_t CS43L22Full::GAIN_0_3959;
constexpr uint8_t CS43L22Full::GAIN_0_4571;
constexpr uint8_t CS43L22Full::GAIN_0_5111;
constexpr uint8_t CS43L22Full::GAIN_0_6047;
constexpr uint8_t CS43L22Full::GAIN_0_7099;
constexpr uint8_t CS43L22Full::GAIN_0_8399;
constexpr uint8_t CS43L22Full::GAIN_1_000;
constexpr uint8_t CS43L22Full::GAIN_1_143;

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

namespace {

const float HP_GAINS[8] = {0.3959f, 0.4571f, 0.5111f, 0.6047f, 0.7099f, 0.8399f, 1.000f, 1.1430f};
const int LIMIT_DB[8] = {0, -3, -6, -9, -12, -18, -24, -30};

int floorInt(float x) {
    int i = static_cast<int>(x);
    return (x < static_cast<float>(i)) ? i - 1 : i;
}

int roundHalfUp(float x) { return floorInt(x + 0.5f); }

int clampInt(int x, int lo, int hi) { return x < lo ? lo : (x > hi ? hi : x); }

float clampF(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }

bool isNear(float v, float c) { return fabsf(v - c) <= c * 1e-4f; }

bool nearAny(float v, const float* list, size_t n) {
    for (size_t i = 0; i < n; i++) if (isNear(v, list[i])) return true;
    return false;
}

uint8_t nearestLimit(int db) {
    uint8_t best = 0;
    int bestErr = abs(db - LIMIT_DB[0]);
    for (uint8_t i = 1; i < 8; i++) {
        int err = abs(db - LIMIT_DB[i]);
        if (err < bestErr) { best = i; bestErr = err; }
    }
    return best;
}

const float FS_ROUND[7] = {8000.0f, 12000.0f, 16000.0f, 24000.0f, 32000.0f, 48000.0f, 96000.0f};
const float FS_44K[4] = {11025.0f, 22050.0f, 44100.0f, 88200.0f};
const float FS_FRAC[4] = {12000000.0f / 1088, 12000000.0f / 544, 12000000.0f / 272, 12000000.0f / 136};
const float FS_27M[6] = {8000.0f, 12000.0f, 16000.0f, 24000.0f, 32000.0f, 48000.0f};

bool isRegisterDocumented(uint8_t reg) {
    switch (reg) {
        case 0x00: case 0x01: case 0x02: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08:
        case 0x09: case 0x0A: case 0x0C: case 0x0D: case 0x0E: case 0x0F: case 0x14: case 0x15:
        case 0x1A: case 0x1B: case 0x1C: case 0x1D: case 0x1E: case 0x1F: case 0x20: case 0x21:
        case 0x22: case 0x23: case 0x24: case 0x25: case 0x26: case 0x27: case 0x28: case 0x29:
        case 0x2E: case 0x2F: case 0x30: case 0x31: case 0x32: case 0x34: case 0x47:
            return true;
        default:
            return false;
    }
}

}  // namespace

// CS43L22Minimal

CS43L22Minimal::CS43L22Minimal(RegisterConnection& connection, OutputPin* resPin)
    : _connection(connection), _resPin(resPin) {}

void CS43L22Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

uint8_t CS43L22Minimal::_read(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void CS43L22Minimal::_write(uint8_t reg, uint8_t value) { _connection.write(reg, &value, 1); }

void CS43L22Minimal::_update(uint8_t reg, uint8_t mask, uint8_t value) {
    _write(reg, static_cast<uint8_t>((_read(reg) & ~mask) | (value & mask)));
}

void CS43L22Minimal::_setBit(uint8_t reg, uint8_t bit, bool on) {
    _update(reg, static_cast<uint8_t>(1u << bit), on ? static_cast<uint8_t>(1u << bit) : 0);
}

uint8_t CS43L22Minimal::_halfDbCode(float db, float lo, float hi) {
    return static_cast<uint8_t>(roundHalfUp(clampF(db, lo, hi) * 2.0f) & 0xFF);
}

uint8_t CS43L22Minimal::_limitedVolumeCode(float db) {
    if (db < -96.0f) return 0x01;
    return static_cast<uint8_t>(roundHalfUp(clampF(db, -96.0f, 0.0f) * 2.0f) & 0xFF);
}

bool CS43L22Minimal::init() {
    if (_resPin != nullptr) {
        _resPin->set(false);
        _delayMs(2);
        _resPin->set(true);
        _delayMs(2);
    }
    if ((_read(REG_ID) & 0xF8) != 0xE0) return false;
    _write(REG_POWER_CTL_1, 0x01);
    _write(REG_POWER_CTL_2, 0xAF);
    _write(REG_CLOCKING_CTL, 0xA0);
    _write(REG_INTERFACE_CTL_1, 0x04);
    _write(REG_PLAYBACK_CTL_1, 0x60);
    _write(REG_MASTER_A_VOL, 0x00);
    _write(REG_MASTER_B_VOL, 0x00);
    // Required initialization settings (datasheet §4.11).
    _write(REG_INIT_00, 0x99);
    _write(REG_INIT_47, 0x80);
    uint8_t v = _read(REG_INIT_32);
    _write(REG_INIT_32, static_cast<uint8_t>(v | 0x80));
    _write(REG_INIT_32, static_cast<uint8_t>(v & 0x7F));
    _write(REG_INIT_00, 0x00);
    return true;
}

void CS43L22Minimal::start() { _write(REG_POWER_CTL_1, 0x9E); }

void CS43L22Minimal::stop() {
    _update(REG_PLAYBACK_CTL_2, 0xF0, 0xF0);
    _update(REG_MISC_CTL, 0x03, 0x00);
    _update(REG_ANALOG_ZC_SR, 0x0F, 0x00);
    _write(REG_POWER_CTL_1, 0x9F);
    _delayMs(1);
}

void CS43L22Minimal::setVolume(float db) {
    uint8_t code = _halfDbCode(db, -102.0f, 12.0f);
    _write(REG_MASTER_A_VOL, code);
    _write(REG_MASTER_B_VOL, code);
}

float CS43L22Minimal::getVolume() {
    return static_cast<float>(static_cast<int8_t>(_read(REG_MASTER_A_VOL))) / 2.0f;
}

void CS43L22Minimal::mute(bool on) { _update(REG_PLAYBACK_CTL_2, 0xC0, on ? 0xC0 : 0x00); }

// CS43L22Full

CS43L22Full::CS43L22Full(RegisterConnection& connection, OutputPin* resPin)
    : CS43L22Minimal(connection, resPin) {}

void CS43L22Full::readId(uint8_t& chipId, uint8_t& revision) {
    uint8_t v = _read(REG_ID);
    chipId = v >> 3;
    revision = v & 0x07;
}

bool CS43L22Full::reset() {
    if (_resPin == nullptr) return false;
    return init();
}

bool CS43L22Full::setOutputs(uint8_t headphone, uint8_t speaker) {
    if (headphone > 3 || speaker > 3) return false;
    _write(REG_POWER_CTL_2, static_cast<uint8_t>((headphone << 6) | (headphone << 4) | (speaker << 2) | speaker));
    return true;
}

void CS43L22Full::setVolumeChannels(float leftDb, float rightDb) {
    _write(REG_MASTER_A_VOL, _halfDbCode(leftDb, -102.0f, 12.0f));
    _write(REG_MASTER_B_VOL, _halfDbCode(rightDb, -102.0f, 12.0f));
}

void CS43L22Full::setHeadphoneVolume(float leftDb, float rightDb) {
    _write(REG_HP_A_VOL, _limitedVolumeCode(leftDb));
    _write(REG_HP_B_VOL, _limitedVolumeCode(rightDb));
}

void CS43L22Full::setSpeakerVolume(float leftDb, float rightDb) {
    _write(REG_SPK_A_VOL, _limitedVolumeCode(leftDb));
    _write(REG_SPK_B_VOL, _limitedVolumeCode(rightDb));
}

void CS43L22Full::setPcmVolume(float leftDb, float rightDb) {
    _update(REG_PCM_A_VOL, 0x7F, _halfDbCode(leftDb, -51.5f, 12.0f));
    _update(REG_PCM_B_VOL, 0x7F, _halfDbCode(rightDb, -51.5f, 12.0f));
}

void CS43L22Full::mutePcm(bool left, bool right) {
    _setBit(REG_PCM_A_VOL, 7, left);
    _setBit(REG_PCM_B_VOL, 7, right);
}

bool CS43L22Full::setHeadphoneGain(uint8_t index) {
    if (index > 7) return false;
    _update(REG_PLAYBACK_CTL_1, 0xE0, static_cast<uint8_t>(index << 5));
    return true;
}

float CS43L22Full::headphoneGain() { return HP_GAINS[_read(REG_PLAYBACK_CTL_1) >> 5]; }

void CS43L22Full::muteOutputs(int8_t headphone, int8_t speaker) {
    if (headphone >= 0) _update(REG_PLAYBACK_CTL_2, 0xC0, headphone ? 0xC0 : 0);
    if (speaker >= 0) _update(REG_PLAYBACK_CTL_2, 0x30, speaker ? 0x30 : 0);
}

void CS43L22Full::setSpeakerMode(bool mono, bool swap, bool mute5050) {
    _update(REG_PLAYBACK_CTL_2, 0x07,
            static_cast<uint8_t>((mono ? 0x02 : 0) | (swap ? 0x04 : 0) | (mute5050 ? 0x01 : 0)));
}

bool CS43L22Full::setClocking(bool autoDetect, uint8_t speed, bool group32k, bool videoClk, uint8_t ratio,
                              bool mclkDiv2) {
    if (speed > 3 || ratio > 3) return false;
    _write(REG_CLOCKING_CTL, static_cast<uint8_t>((autoDetect ? 0x80 : 0) | (speed << 5) | (group32k ? 0x10 : 0) |
                                                  (videoClk ? 0x08 : 0) | (ratio << 1) | (mclkDiv2 ? 1 : 0)));
    return true;
}

bool CS43L22Full::clockingFor(uint32_t mclkHz, float fsHz, CS43L22Clocking& out) {
    float mclk = static_cast<float>(mclkHz);
    bool video = false, div2 = false;
    int ratio = -1;
    if (isNear(mclk, 12288000.0f) || isNear(mclk, 18432000.0f)) {
        if (nearAny(fsHz, FS_ROUND, 7)) ratio = 0;
    } else if (isNear(mclk, 11289600.0f)) {
        if (nearAny(fsHz, FS_44K, 4)) ratio = 0;
    } else if (isNear(mclk, 16934400.0f)) {
        if (nearAny(fsHz, FS_44K, 4)) ratio = 0;
        else if (isNear(fsHz, 16934400.0f / 2112)) ratio = 2;
    } else if (isNear(mclk, 12000000.0f) || isNear(mclk, 24000000.0f)) {
        div2 = mclk > 18000000.0f;
        if (nearAny(fsHz, FS_ROUND, 7)) ratio = 1;
        else if (nearAny(fsHz, FS_FRAC, 4)) ratio = 3;
    } else if (isNear(mclk, 27000000.0f)) {
        video = true;
        if (nearAny(fsHz, FS_27M, 6)) ratio = 1;
        else if (nearAny(fsHz, FS_FRAC, 3)) ratio = 3;
    }
    if (ratio < 0) return false;
    out.group32k = isNear(fsHz, 8000.0f) || isNear(fsHz, 16000.0f) || isNear(fsHz, 32000.0f);
    out.speed = fsHz <= 12100.0f ? 3 : (fsHz <= 24100.0f ? 2 : (fsHz <= 48100.0f ? 1 : 0));
    out.autoDetect = false;
    out.videoClk = video;
    out.ratio = static_cast<uint8_t>(ratio);
    out.mclkDiv2 = div2;
    return true;
}

bool CS43L22Full::setInterface(bool master, uint8_t fmt, uint8_t wordLength, bool dsp, bool invertSclk,
                               bool sclkEqMclk, bool invertSwitch) {
    if (fmt > 2 || wordLength > 3) return false;
    if (dsp && fmt != FMT_LJ) return false;
    _write(REG_INTERFACE_CTL_1, static_cast<uint8_t>((master ? 0x80 : 0) | (invertSclk ? 0x40 : 0) |
                                                     (dsp ? 0x10 : 0) | (fmt << 2) | wordLength));
    _write(REG_INTERFACE_CTL_2, static_cast<uint8_t>((sclkEqMclk ? 0x40 : 0) | (invertSwitch ? 0x08 : 0)));
    return true;
}

bool CS43L22Full::setTone(float trebleDb, float bassDb, uint8_t trebleCorner, uint8_t bassCorner, bool enabled) {
    if (trebleCorner > 3 || bassCorner > 3) return false;
    int treb = clampInt(8 - roundHalfUp(trebleDb / 1.5f), 0, 15);
    int bass = clampInt(8 - roundHalfUp(bassDb / 1.5f), 0, 15);
    _write(REG_TONE_CTL, static_cast<uint8_t>((treb << 4) | bass));
    _update(REG_BEEP_TONE_CFG, 0x1F, static_cast<uint8_t>((trebleCorner << 3) | (bassCorner << 1) | (enabled ? 1 : 0)));
    return true;
}

bool CS43L22Full::setBeep(uint8_t frequency, uint8_t onTime, uint8_t offTime, float volumeDb, uint8_t mode,
                          bool mix) {
    if (frequency > 15 || onTime > 15 || offTime > 7 || mode > 3) return false;
    if (mode == BEEP_OFF) {
        stopBeep();
        return true;
    }
    if ((_read(REG_BEEP_TONE_CFG) & 0xC0) != 0) return false;
    int n = clampInt(roundHalfUp((6.0f - volumeDb) / 2.0f), 0, 31);
    _write(REG_BEEP_FREQ_ON, static_cast<uint8_t>((frequency << 4) | onTime));
    _write(REG_BEEP_VOL_OFF, static_cast<uint8_t>((offTime << 5) | ((6 - n) & 0x1F)));
    _update(REG_BEEP_TONE_CFG, 0xE0, static_cast<uint8_t>((mode << 6) | (mix ? 0 : 0x20)));
    return true;
}

void CS43L22Full::stopBeep() { _update(REG_BEEP_TONE_CFG, 0xC0, 0); }

bool CS43L22Full::setLimiter(bool enabled, int maxDb, int cushionDb, uint8_t release, uint8_t attack,
                             bool limitAll, bool softRampDisable, bool zeroCrossDisable) {
    if (release > 63 || attack > 63) return false;
    _write(REG_LIMIT_CTL_1, static_cast<uint8_t>((nearestLimit(maxDb) << 5) | (nearestLimit(cushionDb) << 2) |
                                                 (softRampDisable ? 0x02 : 0) | (zeroCrossDisable ? 1 : 0)));
    _write(REG_LIMIT_CTL_2, static_cast<uint8_t>((enabled ? 0x80 : 0) | (limitAll ? 0x40 : 0) | release));
    _write(REG_LIMIT_ATTACK, attack);
    return true;
}

void CS43L22Full::setSoftRamp(bool digital, bool analogLeft, bool analogRight) {
    _setBit(REG_MISC_CTL, 1, digital);
    _update(REG_ANALOG_ZC_SR, 0x0A, static_cast<uint8_t>((analogLeft ? 0x02 : 0) | (analogRight ? 0x08 : 0)));
}

void CS43L22Full::setZeroCross(bool digital, bool analogLeft, bool analogRight) {
    _setBit(REG_MISC_CTL, 0, digital);
    _update(REG_ANALOG_ZC_SR, 0x05, static_cast<uint8_t>((analogLeft ? 0x01 : 0) | (analogRight ? 0x04 : 0)));
}

void CS43L22Full::setDeemphasis(bool enabled) { _setBit(REG_MISC_CTL, 2, enabled); }

bool CS43L22Full::setPcmSwap(uint8_t leftSource, uint8_t rightSource) {
    if (leftSource > 3 || rightSource > 3) return false;
    _write(REG_PCM_MIXER_SWAP, static_cast<uint8_t>((leftSource << 6) | (rightSource << 4)));
    return true;
}

void CS43L22Full::invertPcm(bool left, bool right) {
    _update(REG_PLAYBACK_CTL_1, 0x0C, static_cast<uint8_t>((left ? 0x04 : 0) | (right ? 0x08 : 0)));
}

bool CS43L22Full::setPassthrough(bool enabled, uint8_t leftInput, uint8_t rightInput) {
    if (leftInput < 1 || leftInput > 4 || rightInput < 1 || rightInput > 4) return false;
    _write(REG_PASSTHRU_A_SEL, static_cast<uint8_t>(0x80 | (1u << (leftInput - 1))));
    _write(REG_PASSTHRU_B_SEL, static_cast<uint8_t>(0x80 | (1u << (rightInput - 1))));
    _update(REG_MISC_CTL, 0xC0, enabled ? 0xC0 : 0);
    return true;
}

void CS43L22Full::setPassthroughVolume(float leftDb, float rightDb) {
    _write(REG_PASSTHRU_A_VOL, _halfDbCode(leftDb, -60.0f, 12.0f));
    _write(REG_PASSTHRU_B_VOL, _halfDbCode(rightDb, -60.0f, 12.0f));
}

void CS43L22Full::mutePassthrough(bool left, bool right) {
    _setBit(REG_MISC_CTL, 4, left);
    _setBit(REG_MISC_CTL, 5, right);
}

void CS43L22Full::freeze(bool on) { _setBit(REG_MISC_CTL, 3, on); }

bool CS43L22Full::setBatteryCompensation(bool enabled, uint8_t vpRefIndex, bool monitor) {
    if (vpRefIndex > 15) return false;
    _write(REG_BATTERY_COMP, static_cast<uint8_t>((enabled ? 0x80 : 0) | (monitor ? 0x40 : 0) | vpRefIndex));
    return true;
}

float CS43L22Full::readVpVoltage(float vaVolts) {
    return static_cast<float>(_read(REG_VP_LEVEL)) * vaVolts / 63.3f;
}

CS43L22Status CS43L22Full::readStatus() {
    uint8_t v = _read(REG_STATUS);
    CS43L22Status s;
    s.clockError = v & 0x40;
    s.dspAOverflow = v & 0x20;
    s.dspBOverflow = v & 0x10;
    s.pcmAOverflow = v & 0x08;
    s.pcmBOverflow = v & 0x04;
    return s;
}

CS43L22SpeakerStatus CS43L22Full::readSpeakerStatus() {
    uint8_t v = _read(REG_SPEAKER_STATUS);
    CS43L22SpeakerStatus s;
    s.speakerAShort = v & 0x20;
    s.speakerBShort = v & 0x10;
    s.spkrHpPin = v & 0x08;
    return s;
}

bool CS43L22Full::setChargePumpFrequency(uint8_t n) {
    if (n > 15) return false;
    _update(REG_CHARGE_PUMP_FREQ, 0xF0, static_cast<uint8_t>(n << 4));
    return true;
}

bool CS43L22Full::readRegister(uint8_t reg, uint8_t& value) {
    if (!isRegisterDocumented(reg)) return false;
    value = _read(reg);
    return true;
}

bool CS43L22Full::writeRegister(uint8_t reg, uint8_t value) {
    if (!isRegisterDocumented(reg) || reg == 0x01 || reg == 0x2E || reg == 0x30 || reg == 0x31) return false;
    _write(reg, value);
    return true;
}
