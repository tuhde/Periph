#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "OutputPin.h"
#include "CS43L22.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

struct TestPin : public OutputPin {
    std::vector<bool> levels;
    void set(bool high) override { levels.push_back(high); }
};

static uint8_t reg(const I2CConnectionMock& m, uint16_t r) {
    auto it = m.registers().find(r);
    return it == m.registers().end() ? 0 : it->second;
}

static void preload(I2CConnectionMock& m) {
    m.setRegister(0x01, {0xE1});
    m.setRegister(0x32, {0x3B});
}

int main() {
    // --- init ---
    {
        I2CConnectionMock m; preload(m);
        TestPin pin;
        CS43L22Full dac(m, &pin);
        check_true(m.writes().empty() && pin.levels.empty(), "ctor_no_traffic");
        check_true(dac.init(), "init_ok");
        check_true(pin.levels.size() == 2 && !pin.levels[0] && pin.levels[1], "reset_pulse");
        const auto& w = m.writes();
        check_true(w[1] == std::vector<uint8_t>({0x02, 0x01}), "init_power_down_first");
        check_true(reg(m, 0x04) == 0xAF && reg(m, 0x05) == 0xA0 && reg(m, 0x06) == 0x04 && reg(m, 0x0D) == 0x60,
                   "init_defaults");
        std::vector<std::vector<uint8_t>> req;
        for (const auto& x : w) if (x.size() == 2 && (x[0] == 0x00 || x[0] == 0x47 || x[0] == 0x32)) req.push_back(x);
        check_true(req == std::vector<std::vector<uint8_t>>({{0x00, 0x99}, {0x47, 0x80}, {0x32, 0xBB}, {0x32, 0x3B}, {0x00, 0x00}}),
                   "init_required_sequence");
        check_true(reg(m, 0x02) == 0x01, "init_not_powered_up");
    }
    {
        I2CConnectionMock m;
        CS43L22Minimal dac(m);
        check_true(!dac.init(), "bad_id");
        check_true(reg(m, 0x02) == 0, "bad_id_writes_nothing_to_power");
    }

    I2CConnectionMock m; preload(m);
    CS43L22Full dac(m);
    dac.init();

    // --- start / stop ---
    dac.start();
    check_true(reg(m, 0x02) == 0x9E, "start_9e");
    m.setRegister(0x0E, {0x03});
    m.setRegister(0x0A, {0x0F});
    dac.stop();
    check_true(reg(m, 0x02) == 0x9F, "stop_9f");
    check_true((reg(m, 0x0F) & 0xF0) == 0xF0, "stop_mutes");
    check_true((reg(m, 0x0E) & 0x03) == 0 && (reg(m, 0x0A) & 0x0F) == 0, "stop_clears_ramp_zc");

    // --- volume ---
    dac.setVolume(12);
    check_true(reg(m, 0x20) == 0x18, "vol_12");
    dac.setVolume(-102);
    check_true(reg(m, 0x20) == 0x34, "vol_m102");
    dac.setVolume(-500);
    check_true(reg(m, 0x21) == 0x34, "vol_clamp_low");
    dac.setVolume(-0.5f);
    check_true(reg(m, 0x20) == 0xFF && dac.getVolume() == -0.5f, "vol_m0_5");
    dac.setVolumeChannels(-10, 3);
    check_true(reg(m, 0x20) == 0xEC && reg(m, 0x21) == 0x06, "vol_channels");
    dac.setHeadphoneVolume(-20, -20);
    check_true(reg(m, 0x22) == 0xD8 && reg(m, 0x23) == 0xD8, "hp_vol");
    dac.setHeadphoneVolume(-96, -100);
    check_true(reg(m, 0x22) == 0x40 && reg(m, 0x23) == 0x01, "hp_vol_mute");
    dac.setSpeakerVolume(5, -1);
    check_true(reg(m, 0x24) == 0x00 && reg(m, 0x25) == 0xFE, "spk_vol_clamp");
    dac.setPcmVolume(-51.5f, 12);
    check_true(reg(m, 0x1A) == 0x19 && reg(m, 0x1B) == 0x18, "pcm_vol");
    dac.mutePcm(true, false);
    check_true(reg(m, 0x1A) == 0x99 && reg(m, 0x1B) == 0x18, "pcm_mute");
    dac.setPcmVolume(0, 0);
    check_true(reg(m, 0x1A) == 0x80, "pcm_vol_keeps_mute");
    dac.setPassthroughVolume(-60, 12);
    check_true(reg(m, 0x14) == 0x88 && reg(m, 0x15) == 0x18, "pass_vol");

    // --- mute / outputs ---
    m.setRegister(0x0F, {0x00});
    dac.mute(true);
    check_true(reg(m, 0x0F) == 0xC0, "mute_hp");
    dac.mute(false);
    check_true(reg(m, 0x0F) == 0x00, "unmute_hp");
    dac.muteOutputs(-1, 1);
    check_true(reg(m, 0x0F) == 0x30, "mute_spk_only");
    dac.muteOutputs(1, 0);
    check_true(reg(m, 0x0F) == 0xC0, "mute_hp_unmute_spk");
    check_true(dac.setOutputs(CS43L22Full::OUT_ON, CS43L22Full::OUT_OFF) && reg(m, 0x04) == 0xAF, "outputs");
    dac.setOutputs(0, 1);
    check_true(reg(m, 0x04) == 0x05, "outputs_follow");
    check_true(!dac.setOutputs(4, 0), "outputs_invalid");
    dac.setHeadphoneGain(6);
    check_true(reg(m, 0x0D) == 0xC0 && dac.headphoneGain() == 1.0f, "hp_gain");
    check_true(!dac.setHeadphoneGain(8), "hp_gain_invalid");
    dac.setSpeakerMode(true, false, true);
    check_true((reg(m, 0x0F) & 0x07) == 0x03, "spk_mode");

    // --- clocking / interface ---
    dac.setClocking();
    check_true(reg(m, 0x05) == 0xA0, "clocking_default");
    dac.setClocking(false, 3, true, true, 3, true);
    check_true(reg(m, 0x05) == 0x7F, "clocking_all");
    check_true(!dac.setClocking(true, 4), "clocking_invalid");
    CS43L22Clocking c;
    check_true(CS43L22Full::clockingFor(12288000, 48000, c) && !c.autoDetect && c.speed == 1 && !c.group32k &&
                   !c.videoClk && c.ratio == 0 && !c.mclkDiv2, "table_12288_48k");
    check_true(CS43L22Full::clockingFor(12288000, 8000, c) && c.speed == 3 && c.group32k, "table_12288_8k");
    check_true(CS43L22Full::clockingFor(11289600, 44100, c) && c.speed == 1 && c.ratio == 0, "table_11289_44k");
    check_true(CS43L22Full::clockingFor(12000000, 48000, c) && c.ratio == 1 && !c.mclkDiv2, "table_12m_48k");
    check_true(CS43L22Full::clockingFor(24000000, 44117.647f, c) && c.ratio == 3 && c.mclkDiv2 && c.speed == 1,
               "table_24m_44k_frac");
    check_true(CS43L22Full::clockingFor(27000000, 16000, c) && c.speed == 2 && c.group32k && c.videoClk && c.ratio == 1,
               "table_27m_16k");
    check_true(CS43L22Full::clockingFor(16934400, 8018.18f, c) && c.ratio == 2, "table_16934_8018");
    check_true(!CS43L22Full::clockingFor(12288000, 44100, c), "table_unsupported");
    dac.setInterface();
    check_true(reg(m, 0x06) == 0x04 && reg(m, 0x07) == 0x00, "iface_default");
    dac.setInterface(true, CS43L22Full::FMT_RJ, 2, false, true, true, true);
    check_true(reg(m, 0x06) == 0xCA && reg(m, 0x07) == 0x48, "iface_all");
    dac.setInterface(false, 0, 1, true);
    check_true(reg(m, 0x06) == 0x11, "iface_dsp");
    check_true(!dac.setInterface(false, CS43L22Full::FMT_I2S, 0, true), "iface_dsp_needs_lj");

    // --- tone / beep / limiter ---
    dac.setTone(12, -10.5f, 1, 2, true);
    check_true(reg(m, 0x1F) == 0x0F && (reg(m, 0x1E) & 0x1F) == 0x0D, "tone");
    dac.setTone(0, 0, 0, 0, false);
    check_true(reg(m, 0x1F) == 0x88 && (reg(m, 0x1E) & 1) == 0, "tone_flat");
    check_true(dac.setBeep(0xC, 2, 3, 6, CS43L22Full::BEEP_SINGLE), "beep_ok");
    check_true(reg(m, 0x1C) == 0xC2 && reg(m, 0x1D) == 0x66 && (reg(m, 0x1E) >> 6) == 1, "beep_regs");
    check_true(!dac.setBeep(1, 1, 1, 0, 1), "beep_active_refused");
    dac.stopBeep();
    check_true((reg(m, 0x1E) >> 6) == 0, "beep_stop");
    dac.setBeep(1, 1, 0, -56, 3, false);
    check_true((reg(m, 0x1D) & 0x1F) == 0x07 && (reg(m, 0x1E) & 0xE0) == 0xE0, "beep_vol_min");
    dac.setBeep(1, 1, 0, 0, 0);
    check_true((reg(m, 0x1E) >> 6) == 0, "beep_off_mode");
    dac.setBeep(1, 1, 0, 0, 2);
    check_true((reg(m, 0x1D) & 0x1F) == 0x03, "beep_vol_0db");
    dac.stopBeep();
    check_true(!dac.setBeep(16, 0, 0, 0, 1), "beep_invalid");
    dac.setLimiter(true, -6, -12, 0x20, 5, false, true, true);
    check_true(reg(m, 0x27) == 0x53 && reg(m, 0x28) == 0xA0 && reg(m, 0x29) == 5, "limiter");
    dac.setLimiter(false, -29, 0);
    check_true((reg(m, 0x27) >> 5) == 7 && reg(m, 0x28) == 0x7F, "limiter_nearest");
    check_true(!dac.setLimiter(true, 0, 0, 64), "limiter_invalid");

    // --- ramp / zero-cross / dsp ---
    m.setRegister(0x0E, {0x00});
    m.setRegister(0x0A, {0xA5});
    dac.setSoftRamp(true, true, false);
    check_true(reg(m, 0x0E) == 0x02 && reg(m, 0x0A) == 0xA7, "soft_ramp");
    dac.setZeroCross(true, false, true);
    check_true(reg(m, 0x0E) == 0x03 && reg(m, 0x0A) == 0xA6, "zero_cross");
    dac.setDeemphasis(true);
    check_true(reg(m, 0x0E) & 0x04, "deemph");
    dac.freeze(true);
    check_true(reg(m, 0x0E) & 0x08, "freeze_on");
    dac.freeze(false);
    check_true(!(reg(m, 0x0E) & 0x08), "freeze_off");
    dac.setPcmSwap(3, 1);
    check_true(reg(m, 0x26) == 0xD0, "pcm_swap");
    dac.invertPcm(true, false);
    check_true((reg(m, 0x0D) & 0x0C) == 0x04, "invert_pcm");
    dac.setPassthrough(true, 2, 4);
    check_true(reg(m, 0x08) == 0x82 && reg(m, 0x09) == 0x88 && (reg(m, 0x0E) & 0xC0) == 0xC0, "passthrough");
    check_true(!dac.setPassthrough(true, 0, 1), "passthrough_invalid");
    dac.mutePassthrough(true, false);
    check_true((reg(m, 0x0E) & 0x30) == 0x10, "mute_passthrough");

    // --- battery / status ---
    dac.setBatteryCompensation(true, 9, true);
    check_true(reg(m, 0x2F) == 0xC9, "battery");
    m.setRegister(0x30, {0x40});
    check_true(fabsf(dac.readVpVoltage(2.5f) - 64 * 2.5f / 63.3f) < 1e-4f, "vp_voltage");
    m.setRegister(0x2E, {0x4C});
    CS43L22Status st = dac.readStatus();
    check_true(st.clockError && st.pcmAOverflow && st.pcmBOverflow && !st.dspAOverflow && !st.dspBOverflow, "status");
    m.setRegister(0x31, {0x28});
    CS43L22SpeakerStatus sp = dac.readSpeakerStatus();
    check_true(sp.speakerAShort && sp.spkrHpPin && !sp.speakerBShort, "speaker_status");
    m.setRegister(0x34, {0x5F});
    dac.setChargePumpFrequency(7);
    check_true(reg(m, 0x34) == 0x7F && !dac.setChargePumpFrequency(16), "charge_pump");

    // --- id / raw / reset ---
    uint8_t id, rev, v;
    dac.readId(id, rev);
    check_true(id == 0x1C && rev == 1, "read_id");
    check_true(dac.readRegister(0x01, v) && v == 0xE1, "read_register");
    check_true(!dac.readRegister(0x03, v), "read_register_reserved");
    check_true(dac.writeRegister(0x20, 0x10) && reg(m, 0x20) == 0x10, "write_register");
    check_true(!dac.writeRegister(0x2E, 0) && !dac.writeRegister(0x33, 0), "write_register_rejected");
    check_true(!dac.reset(), "reset_no_pin");
    {
        I2CConnectionMock m2; preload(m2);
        TestPin pin;
        CS43L22Full d2(m2, &pin);
        d2.init();
        d2.start();
        check_true(d2.reset() && reg(m2, 0x02) == 0x01, "reset_reinit");
    }

    printf("Passed: %d  Failed: %d\n", passed, failed);
    return failed ? 1 : 0;
}
