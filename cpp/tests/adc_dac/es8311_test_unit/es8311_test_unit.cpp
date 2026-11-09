#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "ES8311.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static uint8_t reg(const I2CConnectionMock& m, uint16_t r) {
    auto it = m.registers().find(r);
    return it == m.registers().end() ? 0 : it->second;
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

int main() {
    // --- init sequence -----------------------------------------------------
    {
        I2CConnectionMock m;
        ES8311Minimal chip(m);
        const auto& w = m.writes();
        check_true(eq(w[0], {0x00, 0x1F}), "init_reset_first");
        check_true(eq(w[1], {0x00, 0x00}) && eq(w[2], {0x00, 0x80}), "init_reset_release");
        check_true(reg(m, 0x01) == 0x3F, "init_clk1");
        check_true(reg(m, 2) == 0x00 && reg(m, 3) == 0x10 && reg(m, 4) == 0x10 && reg(m, 5) == 0x00,
                   "init_clock_preset_12288_48k");
        check_true(reg(m, 6) == 0x03 && reg(m, 7) == 0x00 && reg(m, 8) == 0xFF, "init_bclk_lrck_dividers");
        check_true(reg(m, 0x09) == 0x0C && reg(m, 0x0A) == 0x0C, "init_serial_i2s_16");
        check_true(reg(m, 0x0D) == 0x01 && reg(m, 0x0E) == 0x02 && reg(m, 0x12) == 0x00 &&
                   reg(m, 0x13) == 0x10 && reg(m, 0x1C) == 0x6A && reg(m, 0x37) == 0x08, "init_vendor_regs");
        check_true(reg(m, 0x14) == 0x18, "init_mic_24db");
        check_true(reg(m, 0x17) == 0xBF, "init_adc_volume_0db");
        check_true(reg(m, 0x32) == 0x00, "init_dac_silent");
        check_true(reg(m, 0x16) == 0x04, "init_adc_scale");

        chip.set_volume(0);
        check_true(reg(m, 0x32) == 0xBF, "volume_0db");
        chip.set_volume(-95.5f);
        check_true(reg(m, 0x32) == 0x00, "volume_min");
        chip.set_volume(32);
        check_true(reg(m, 0x32) == 0xFF, "volume_max");
        chip.set_volume(100);
        check_true(reg(m, 0x32) == 0xFF, "volume_clamps_high");
        chip.set_volume(-10.2f);
        check_true(reg(m, 0x32) == 191 - 20, "volume_rounds");
        m.setRegister(0x31, {0x81});
        chip.set_mute(true);
        check_true(reg(m, 0x31) == 0xE1, "mute_sets_bits_6_5_keeps_rest");
        chip.set_mute(false);
        check_true(reg(m, 0x31) == 0x81, "unmute_clears_bits_6_5");
        chip.set_mic_gain(30);
        check_true(reg(m, 0x14) == 0x1A, "mic_gain_30db");
        chip.set_mic_gain(7.4f);
        check_true(reg(m, 0x14) == 0x12, "mic_gain_rounds_3db");
        chip.set_mic_gain(99);
        check_true((reg(m, 0x14) & 0x0F) == 10, "mic_gain_clamps");
    }
    {
        I2CConnectionMock m;
        ES8311Minimal chip(m, 24576000, 48000);
        check_true(reg(m, 0x02) == 0x20, "preset_24576_48k");
    }
    {
        I2CConnectionMock m;
        ES8311Minimal chip(m, 12288000, 16000);
        const uint8_t r02 = reg(m, 0x02);
        static const int mults[4] = {1, 2, 4, 8};
        double imclk = 12288000.0 / (((r02 >> 5) & 7) + 1) * mults[(r02 >> 3) & 3];
        double ratio = imclk / ((reg(m, 0x05) & 0x0F) + 1) / 16000.0;
        check_true(ratio == 256 || ratio == 384 || ratio == 512 || ratio == 768, "computed_16k_ratio_allowed");
        check_true(reg(m, 0x03) == reg(m, 0x04) && reg(m, 0x03) >= 16 && reg(m, 0x03) <= 63, "computed_16k_osr_code");
    }

    // --- Full ---------------------------------------------------------------
    I2CConnectionMock m;
    ES8311Full full(m);
    full.set_volume(-20);
    check_true(full.get_volume() == -20.0f, "get_volume");
    full.set_mic_gain(21);
    check_true(full.get_mic_gain() == 21.0f, "get_mic_gain");

    size_t n = m.writes().size();
    full.reset();
    check_true(m.writes().size() == n + 3 && eq(m.writes()[n], {0, 0x1F}) && eq(m.writes()[n + 1], {0, 0}) &&
               eq(m.writes()[n + 2], {0, 0x80}), "reset_sequence");

    full.power_down();
    check_true(reg(m, 0x00) == 0x1F, "power_down_clears_csm");
    check_true((reg(m, 0x31) & 0x60) == 0x60, "power_down_mutes");
    check_true(reg(m, 0x0D) == 0xFC && (reg(m, 0x0E) & 0x70) == 0x70, "power_down_pwr_bits");
    full.power_up();
    check_true(reg(m, 0x00) == 0x80 && reg(m, 0x0D) == 0x01, "power_up_csm_on");

    full.set_low_power(true, false, false, false, false, false, false, true);
    check_true(reg(m, 0x0F) == 0x81, "low_power_bits");
    full.set_low_power(false, true, false, true, true, false, true, false);
    check_true(reg(m, 0x0F) == (0x40 | 0x10 | 0x08 | 0x02), "low_power_other_bits");

    check_true(full.configure_clock(12288000, 48000, true, true) && reg(m, 0x01) == 0xFF, "clock_from_bclk_invert");
    check_true(full.configure_clock(12288000, 48000) && reg(m, 0x01) == 0x3F, "clock_default_flags");
    check_true(!full.configure_clock(1000, 48000), "clock_invalid_returns_false");

    check_true(full.configure_serial(ES8311Full::FORMAT_LEFT_JUSTIFIED, 24), "serial_lj_ok");
    check_true(reg(m, 0x09) == 0x01 && reg(m, 0x0A) == 0x01, "serial_lj_24");
    full.configure_serial(ES8311Full::FORMAT_DSP_B, 32, false, false, false, true);
    check_true(reg(m, 0x09) == (0x80 | 0x20 | (4 << 2) | 3), "serial_dsp_b_32_right");
    check_true(reg(m, 0x0A) == (0x20 | (4 << 2) | 3), "serial_dsp_b_out");
    full.configure_serial(ES8311Full::FORMAT_DSP_A, 16, false, false, true);
    check_true(reg(m, 0x0A) == (0x20 | (3 << 2) | 3), "serial_dsp_a_lrp");
    check_true((reg(m, 0x00) & 0x44) == 0x04, "serial_slave_rst_mst");
    full.set_sdp_mute(true, false);
    check_true((reg(m, 0x09) & 0x40) && !(reg(m, 0x0A) & 0x40), "sdp_mute_in_only");
    full.configure_serial(ES8311Full::FORMAT_I2S, 16);
    check_true(reg(m, 0x09) & 0x40, "serial_keeps_mute_bit");
    full.configure_serial(ES8311Full::FORMAT_I2S, 16, true, false, false, false, 4, 256);
    check_true((reg(m, 0x00) & 0x44) == 0x40, "serial_master_msc");
    check_true(reg(m, 0x06) == 0x03 && (reg(m, 0x07) & 0x0F) == 0x00 && reg(m, 0x08) == 0xFF, "serial_master_dividers");
    full.configure_serial(ES8311Full::FORMAT_I2S, 16, true, true, false, false, 32, 64, false);
    check_true(reg(m, 0x06) == (0x40 | 0x20 | 24), "serial_master_table_div");
    check_true(!full.configure_serial(9), "serial_bad_format");
    check_true(!full.configure_serial(ES8311Full::FORMAT_I2S, 8), "serial_bad_word");

    full.set_tristate(true, false);
    check_true((reg(m, 0x07) & 0x30) == 0x20, "tristate_bclk");
    full.set_tristate(false, true);
    check_true((reg(m, 0x07) & 0x30) == 0x10, "tristate_adcdat");

    full.set_input(ES8311Full::INPUT_DIGITAL);
    check_true((reg(m, 0x14) & 0x70) == 0x40 && (reg(m, 0x0E) & 0x40), "input_digital");
    full.set_input(ES8311Full::INPUT_ANALOG);
    check_true((reg(m, 0x14) & 0x70) == 0x10 && !(reg(m, 0x0E) & 0x40), "input_analog");
    full.set_input(ES8311Full::INPUT_NONE);
    check_true((reg(m, 0x14) & 0x70) == 0x00 && (reg(m, 0x0E) & 0x40), "input_none");
    check_true(!full.set_input(7), "input_bad");
    full.configure_dmic(true);
    check_true((reg(m, 0x15) & 1) == 1, "dmic_falling");
    full.configure_dmic();
    check_true((reg(m, 0x15) & 1) == 0, "dmic_rising");

    full.set_adc_volume(-95.5f);
    check_true(reg(m, 0x17) == 0, "adc_volume_min");
    check_true(full.configure_adc(42, 128, false, false, 0x05, 0x0A), "adc_cfg_ok");
    check_true((reg(m, 0x16) & 0x07) == 7, "adc_scale_42");
    check_true(reg(m, 0x03) == 32, "adc_osr_128");
    check_true((reg(m, 0x1C) & 0x3F) == 0x0A && (reg(m, 0x1B) & 0x1F) == 0x05, "adc_hpf_off_coeff");
    full.configure_adc(24, 64, true, true);
    check_true(reg(m, 0x03) == (0x40 | 32) && (reg(m, 0x1C) & 0x20), "adc_double_speed");
    check_true(!full.configure_adc(24, 8), "adc_bad_osr");
    check_true(!full.configure_adc(24, 20, true), "adc_bad_double_osr");
    full.set_adc_ramp(7);
    check_true((reg(m, 0x15) >> 4) == 7, "adc_ramp");
    full.set_adc_ramp(99);
    check_true((reg(m, 0x15) >> 4) == 15, "adc_ramp_clamps");

    full.configure_alc(true, -12.0f, -18.1f, 3, true, 2, -60);
    check_true(reg(m, 0x18) == 0xC3, "alc1");
    check_true(reg(m, 0x19) == ((7 << 4) | 3), "alc2_levels");
    check_true(reg(m, 0x1A) == ((2 << 4) | 6), "alc_automute");
    full.configure_alc(false);
    check_true(reg(m, 0x18) == 0 && reg(m, 0x19) == 0xF0 && reg(m, 0x1A) == 0, "alc_off_defaults");

    const uint32_t adc_eq[5] = {1, 2, 3, 4, 0x3FFFFFFF};
    check_true(full.set_adc_eq(adc_eq), "adc_eq_ok");
    check_true((reg(m, 0x1C) & 0x40) == 0, "adc_eq_enable");
    check_true(reg(m, 0x1D) == 0 && reg(m, 0x1E) == 0 && reg(m, 0x1F) == 0 && reg(m, 0x20) == 1, "adc_eq_b0");
    check_true(reg(m, 0x2D) == 0x3F && reg(m, 0x2E) == 0xFF && reg(m, 0x2F) == 0xFF && reg(m, 0x30) == 0xFF, "adc_eq_b2_30bit");
    bool burst = false;
    for (const auto& w : m.writes()) if (w.size() == 21 && w[0] == 0x1D) burst = true;
    check_true(burst, "adc_eq_burst");
    full.bypass_adc_eq();
    check_true((reg(m, 0x1C) & 0x40) == 0x40, "adc_eq_bypass");
    const uint32_t bad_eq[5] = {1u << 30, 0, 0, 0, 0};
    check_true(!full.set_adc_eq(bad_eq), "adc_eq_range");
    const uint32_t dac_eq[3] = {0x100, 0x200, 0x300};
    check_true(full.set_dac_eq(dac_eq), "dac_eq_ok");
    check_true((reg(m, 0x37) & 0x08) == 0, "dac_eq_enable");
    check_true(reg(m, 0x3B) == 0x00 && reg(m, 0x3A) == 0x01 && reg(m, 0x3F) == 0x00 && reg(m, 0x3E) == 0x02 &&
               reg(m, 0x42) == 0x03, "dac_eq_coeffs");
    full.bypass_dac_eq();
    check_true((reg(m, 0x37) & 0x08) == 0x08, "dac_eq_bypass");

    full.set_dac_ramp(8);
    check_true((reg(m, 0x37) >> 4) == 8 && (reg(m, 0x37) & 0x08), "dac_ramp");
    full.configure_drc(true, -12.0f, -24.1f, 5);
    check_true(reg(m, 0x34) == 0x85 && reg(m, 0x35) == ((7 << 4) | 1), "drc_regs");
    full.set_dac_mute_options(true, false, true);
    check_true((reg(m, 0x31) & 0xE0) == 0xC0, "mute_options");
    full.set_dac_mute_options();
    check_true((reg(m, 0x31) & 0xE0) == 0x60, "mute_options_defaults");

    full.set_loopback(true);
    check_true(reg(m, 0x44) & 0x80, "loopback_on");
    check_true(full.set_adcdat_mode(ES8311Full::ADCDAT_DAC_ADC), "adcdat_ok");
    check_true(reg(m, 0x44) == 0xC0, "adcdat_dac_adc_keeps_loopback");
    full.set_adcdat_mode(ES8311Full::ADCDAT_DAC_DAC);
    check_true(((reg(m, 0x44) >> 4) & 7) == 6, "adcdat_dac_dac");
    full.set_loopback(false);
    check_true(reg(m, 0x44) == 0x60, "loopback_off_keeps_mode");
    check_true(!full.set_adcdat_mode(3), "adcdat_bad");

    full.write_register(0x40, 0x34);
    check_true(reg(m, 0x40) == 0x34 && full.read_register(0x40) == 0x34, "raw_register");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
