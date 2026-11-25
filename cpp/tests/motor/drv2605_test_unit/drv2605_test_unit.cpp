#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include <vector>
#include "I2CConnectionMock.h"
#include "DRV2605.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Mock whose DEV_RESET bit and GO bit self-clear like the real chip.
class ChipMock : public I2CConnectionMock {
public:
    ChipMock() { setRegister(0x00, {0x60}); }
protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (len >= 2 && data[0] == 0x01) setRegister(0x01, {(uint8_t)(data[1] & 0x7F)});
        if (len >= 2 && data[0] == 0x0C) setRegister(0x0C, {0});
    }
};

// Last write payload (bytes after the register) to @p reg; empty if none.
static std::vector<uint8_t> lastWrite(const I2CConnectionMock& mock, uint8_t reg) {
    std::vector<uint8_t> v;
    for (const auto& w : mock.writes())
        if (w.size() >= 2 && w[0] == reg) v.assign(w.begin() + 1, w.end());
    return v;
}
static int lastByte(const I2CConnectionMock& mock, uint8_t reg) {
    std::vector<uint8_t> v = lastWrite(mock, reg);
    return v.empty() ? -1 : v[0];
}
static bool wrote(const I2CConnectionMock& mock, uint8_t reg, uint8_t value) {
    for (const auto& w : mock.writes()) if (w.size() == 2 && w[0] == reg && w[1] == value) return true;
    return false;
}

int main() {
    // init
    {
        ChipMock mock;
        DRV2605Minimal chip(mock, false);
        check_true(chip.isReady(), "init_ready");
        check_true(mock.writes()[0] == std::vector<uint8_t>({0x01, 0x80}), "init_reset_first");
        check_true(wrote(mock, 0x01, 0x00), "init_leaves_standby");
        check_true(lastByte(mock, 0x1A) == 0x36, "init_erm_feedback");
        check_true(lastByte(mock, 0x03) == 0x01, "init_erm_library_a");
    }
    {
        ChipMock mock;
        DRV2605Minimal chip(mock, true);
        check_true(lastByte(mock, 0x1A) == 0xB6, "init_lra_feedback");
        check_true(lastByte(mock, 0x03) == 0x06, "init_lra_library");
    }
    {
        ChipMock mock;
        mock.setRegister(0x00, {0xE0});
        DRV2605Minimal chip(mock);
        check_true(!chip.isReady(), "init_wrong_device_id");
    }

    // minimal playback
    {
        ChipMock mock;
        DRV2605Minimal chip(mock);
        check_true(chip.playEffect(47), "play_effect_ok");
        check_true(lastWrite(mock, 0x04) == std::vector<uint8_t>({47, 0}), "play_effect_slots");
        check_true(lastByte(mock, 0x0C) == 1, "play_effect_go");
        check_true(!chip.playEffect(0), "play_effect_rejects_0");
        check_true(!chip.playEffect(124), "play_effect_rejects_124");
        chip.stop();
        check_true(lastByte(mock, 0x0C) == 0, "stop_clears_go");
        mock.setRegister(0x0C, {1});
        check_true(chip.isPlaying(), "is_playing_true");
        mock.setRegister(0x0C, {0});
        check_true(!chip.isPlaying(), "is_playing_false");
    }

    // sequence
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        DRV2605Full::SequenceStep s[3] = {DRV2605Full::SequenceStep::effect(1), DRV2605Full::SequenceStep::wait(100),
                                          DRV2605Full::SequenceStep::effect(10)};
        check_true(chip.playSequence(s, 3), "sequence_ok");
        check_true(lastWrite(mock, 0x04) == std::vector<uint8_t>({1, 0x8A, 10, 0}), "sequence_encoding");
        DRV2605Full::SequenceStep e[8];
        for (int i = 0; i < 8; i++) e[i] = DRV2605Full::SequenceStep::effect(i + 1);
        chip.playSequence(e, 8);
        check_true(lastWrite(mock, 0x04) == std::vector<uint8_t>({1, 2, 3, 4, 5, 6, 7, 8}), "sequence_eight_no_terminator");
        check_true(!chip.playSequence(e, 9), "sequence_too_long");
        DRV2605Full::SequenceStep bad[1] = {DRV2605Full::SequenceStep::wait(2000)};
        check_true(!chip.playSequence(bad, 1), "sequence_bad_wait");
        mock.setRegister(0x0C, {0});
        check_true(chip.waitDone(50), "wait_done_true");
        mock.setRegister(0x0C, {1});
        check_true(!chip.waitDone(20), "wait_done_timeout");
    }

    // mode / bits
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        chip.setMode(5);
        check_true(lastByte(mock, 0x01) == 0x05, "set_mode_5");
        chip.standby();
        check_true(lastByte(mock, 0x01) == 0x45, "standby_bit");
        chip.wake();
        check_true(lastByte(mock, 0x01) == 0x05, "wake_bit");
        chip.setHiZ(true);
        check_true((lastByte(mock, 0x03) & 0x10) == 0x10, "hi_z_set");
        chip.setLibrary(6);
        check_true(lastByte(mock, 0x03) == 0x16, "library_preserves_hi_z");
        check_true(!chip.setLibrary(7), "library_range");
        mock.setRegister(0x1A, {0x36});
        chip.setActuator(true);
        check_true(lastByte(mock, 0x1A) == 0xB6, "set_actuator_lra");
    }

    // RTP
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        chip.rtpLevel(0.5f);
        check_true(wrote(mock, 0x01, 0x05), "rtp_level_mode");
        check_true(lastByte(mock, 0x02) == 64, "rtp_level_value");
        chip.rtpWrite(200);
        check_true(lastByte(mock, 0x02) == 200, "rtp_write_raw");
        chip.stop();
        check_true(lastByte(mock, 0x02) == 0 && lastByte(mock, 0x01) == 0x00, "stop_leaves_rtp");
        check_true(!chip.rtpLevel(1.5f), "rtp_level_range");
        chip.setRtpFormat(true);
        check_true((lastByte(mock, 0x1D) & 0x08) == 0x08, "rtp_format_unsigned");
    }

    // loop constraints
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        mock.setRegister(0x1D, {0x20});
        check_true(!chip.setBidirectional(false), "unidirectional_rejected_open_loop");
        chip.setOpenLoop(false);
        check_true(lastByte(mock, 0x1D) == 0x00, "erm_open_loop_clear");
        check_true(chip.setBidirectional(false), "unidirectional_closed_loop_ok");
        check_true((lastByte(mock, 0x1C) & 0x80) == 0, "unidirectional_closed_loop");
    }

    // voltage conversions
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        mock.setRegister(0x1A, {0x36});
        chip.setRatedVoltage(3.0f);
        check_true(lastByte(mock, 0x16) == 141, "rated_erm_3v");
        chip.setRatedVoltageCode(77);
        check_true(lastByte(mock, 0x16) == 77, "rated_raw_code");
        mock.setRegister(0x1D, {0x20});
        chip.setOverdriveClamp(3.0f);
        check_true(lastByte(mock, 0x17) == 137, "clamp_erm_open_3v");
        mock.setRegister(0x1D, {0x00});
        check_true(!chip.setOverdriveClamp(3.0f), "clamp_erm_closed_rejected");
        mock.setRegister(0x1A, {0xB6});
        mock.setRegister(0x1C, {0x30});
        chip.setRatedVoltage(2.0f, 200.0f);
        check_true(lastByte(mock, 0x16) == 81, "rated_lra_2v_200hz");
        check_true(!chip.setRatedVoltage(2.0f), "rated_lra_needs_freq");
        chip.setDriveTime(2.5f);
        check_true((lastByte(mock, 0x1B) & 0x1F) == 20, "drive_time_lra");
        mock.setRegister(0x1A, {0x36});
        chip.setDriveTime(5.0f);
        check_true((lastByte(mock, 0x1B) & 0x1F) == 20, "drive_time_erm");
    }

    // feedback / offsets
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        mock.setRegister(0x1A, {0x80});
        chip.setFeedback(2, 2, 1);
        check_true(lastByte(mock, 0x1A) == (0x80 | (2 << 4) | (2 << 2) | 1), "feedback_preserves_type");
        chip.setTimeOffsets(10, -5, 0, -640);
        check_true(lastWrite(mock, 0x0D) == std::vector<uint8_t>({2, 0xFF, 0, 0x80}), "time_offsets_signed");
        check_true(!chip.setTimeOffsets(0, 0, 0, 700), "time_offsets_range");
    }

    // calibration
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        mock.setRegister(0x18, {0x0D, 0x6D});
        mock.setRegister(0x1A, {0x36});
        DRV2605Full::Calibration c = chip.calibrate(false, 3.0f, 3.0f);
        check_true(wrote(mock, 0x01, 0x07), "calibrate_enters_mode7");
        check_true(lastByte(mock, 0x16) == 141 && lastByte(mock, 0x17) == 137, "calibrate_sets_inputs");
        check_true((lastByte(mock, 0x1E) & 0x30) == 0x30, "calibrate_auto_cal_time");
        check_true(wrote(mock, 0x0C, 0x01), "calibrate_go");
        check_true(c.ok && c.comp == 0x0D && c.bemf == 0x6D && c.bemfGain == 2, "calibrate_result");
        check_true(lastByte(mock, 0x01) == 0x00, "calibrate_restores_mode0");
        mock.setRegister(0x00, {0x68});
        check_true(!chip.calibrate(false, 3.0f, 3.0f).ok, "calibrate_failure_flag");
        mock.setRegister(0x00, {0x60});
        check_true(!chip.calibrate(true, 2.0f, 2.5f).ok, "calibrate_lra_needs_freq");
        chip.calibrate(true, 2.0f, 2.5f, 200.0f);
        check_true((lastByte(mock, 0x1A) & 0x80) == 0x80, "calibrate_lra_feedback");
        chip.setCalibration(0x20, 0x80, 3);
        check_true(lastWrite(mock, 0x18) == std::vector<uint8_t>({0x20, 0x80}) && (lastByte(mock, 0x1A) & 0x03) == 3,
                   "set_calibration");
        DRV2605Full::Calibration g = chip.getCalibration();
        check_true(g.comp == 0x20 && g.bemf == 0x80 && g.bemfGain == 3, "get_calibration");
    }

    // diagnose
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        mock.setRegister(0x0C, {0});
        check_true(chip.diagnose() && wrote(mock, 0x01, 0x06), "diagnose_ok");
        mock.setRegister(0x00, {0x68});
        check_true(!chip.diagnose(), "diagnose_fail");
    }

    // audio to vibe
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        chip.setAudioToVibe(true, 30, 150, 0.0f, 1.8f, 0.0f, 1.0f);
        check_true(lastWrite(mock, 0x11) == std::vector<uint8_t>({(2 << 2) | 2, 0, 255, 0, 255}), "atv_registers");
        check_true(lastByte(mock, 0x01) == 0x04, "atv_mode4");
        check_true((lastByte(mock, 0x1B) & 0x20) == 0x20, "atv_ac_couple");
        check_true(!chip.setAudioToVibe(true, 20, 99), "atv_bad_filter");
    }

    // status / readouts
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        mock.setRegister(0x00, {0x60 | 0x08 | 0x01});
        DRV2605Full::Status s = chip.readStatus();
        check_true(s.diagFailed && !s.fbTimeout && !s.overTemp && s.overCurrent, "read_status");
        check_true(chip.readDeviceId() == 3, "read_device_id");
        mock.setRegister(0x21, {255});
        check_true(fabsf(chip.readSupplyVoltage() - 5.6f) < 1e-4f, "supply_voltage");
        mock.setRegister(0x22, {0});
        check_true(chip.readLraFrequency() == 0.0f, "lra_freq_none");
        mock.setRegister(0x22, {52});
        check_true(fabsf(chip.readLraFrequency() - 195.3f) < 0.2f, "lra_freq");
    }

    // OTP
    {
        ChipMock mock;
        DRV2605Full chip(mock);
        size_t n = mock.writes().size();
        check_true(!chip.programOtp() && mock.writes().size() == n, "otp_requires_confirm");
        mock.setRegister(0x1E, {0x04});
        check_true(chip.otpProgrammed(), "otp_status");
        check_true(!chip.programOtp(true), "otp_already_programmed");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
