#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "ADS7828.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool close(float a, float b) { return fabsf(a - b) < 1e-5f; }

static bool lastIs(const I2CConnectionMock& mock, uint8_t cmd) {
    const auto& w = mock.writes();
    return !w.empty() && w.back().size() == 1 && w.back()[0] == cmd;
}

static bool nthLastIs(const I2CConnectionMock& mock, size_t n, uint8_t cmd) {
    const auto& w = mock.writes();
    return w.size() > n && w[w.size() - 1 - n].size() == 1 && w[w.size() - 1 - n][0] == cmd;
}

int main() {
    // Internal reference (default): begin() sends the lone reference-on command 0x08.
    {
        I2CConnectionMock mock;
        ADS7828Full adc(mock);
        check_true(mock.writes().empty(), "constructor_no_traffic");
        adc.begin();
        check_true(mock.writes().size() == 1 && lastIs(mock, 0x08), "init_enables_internal_reference");
        check_true(adc.getReference() == 2.5f && adc.usesInternalReference(), "init_internal_vref");

        // readRaw(0): SE, C=0, PD=11 -> 0x8C; bytes 0x0A 0xBC -> 0xABC.
        mock.setRegister(0x8C, {0x0A, 0xBC});
        check_true(adc.readRaw(0) == 0xABC, "read_raw_ch0");
        check_true(lastIs(mock, 0x8C), "read_raw_ch0_command");

        // Interleaved encoding: CH1 -> 0xCC, CH2 -> 0x9C, CH7 -> 0xFC.
        mock.setRegister(0xCC, {0x0F, 0xFF});
        check_true(adc.readRaw(1) == 0xFFF, "read_raw_ch1");
        check_true(lastIs(mock, 0xCC), "read_raw_ch1_command");
        adc.readRaw(2);
        check_true(lastIs(mock, 0x9C), "read_raw_ch2_command");
        adc.readRaw(7);
        check_true(lastIs(mock, 0xFC), "read_raw_ch7_command");

        // Upper nibble of byte0 is masked off.
        mock.setRegister(0x8C, {0xF1, 0x23});
        check_true(adc.readRaw(0) == 0x123, "read_raw_masks_high_nibble");

        // Out-of-range channels clamp.
        adc.readRaw(99);
        check_true(lastIs(mock, 0xFC), "read_raw_clamps_high");
        adc.readRaw(-3);
        check_true(lastIs(mock, 0x8C), "read_raw_clamps_low");

        mock.setRegister(0x8C, {0x08, 0x00});
        check_true(close(adc.readVoltage(0), 1.25f), "read_voltage");
        float all[8];
        adc.readAll(all);
        check_true(close(all[0], 1.25f) && all[7] == 0.0f, "read_all");

        // Differential: pair 0 -> 0x0C; pair 3 inverted -> 0x7C.
        mock.setRegister(0x0C, {0x04, 0x00});
        check_true(adc.readDifferentialRaw(0) == 0x400, "read_differential_raw");
        check_true(lastIs(mock, 0x0C), "read_differential_command");
        adc.readDifferentialRaw(3, true);
        check_true(lastIs(mock, 0x7C), "read_differential_inverted_command");
        check_true(close(adc.readDifferential(0), 0x400 / 4096.0f * 2.5f), "read_differential_voltage");

        // Signed differential = normal - inverted.
        mock.setRegister(0x0C, {0x00, 0x00});
        mock.setRegister(0x4C, {0x02, 0x00});
        check_true(close(adc.readSignedDifferential(0), -0x200 / 4096.0f * 2.5f), "read_signed_differential_negative");
        mock.setRegister(0x0C, {0x02, 0x00});
        mock.setRegister(0x4C, {0x00, 0x00});
        check_true(close(adc.readSignedDifferential(0), 0x200 / 4096.0f * 2.5f), "read_signed_differential_positive");

        check_true(close(adc.lsb(), 2.5f / 4096.0f), "lsb");
        mock.setRegister(0x8C, {0x00, 0x01});
        mock.setRegister(0xCC, {0x00, 0x02});
        const uint8_t chans[2] = {0, 1};
        uint16_t scan[2];
        adc.readScan(chans, 2, scan);
        check_true(scan[0] == 1 && scan[1] == 2, "read_scan");

        // setPowerMode validation.
        check_true(!adc.setPowerMode(1), "set_power_mode_rejects_pd1_clear");
        check_true(!adc.setPowerMode(7), "set_power_mode_rejects_out_of_range");
        check_true(adc.setPowerMode(ADS7828Full::REF_ON_ADC_OFF), "set_power_mode_accepts_pd2");
        adc.readRaw(0);
        check_true(lastIs(mock, 0x88), "set_power_mode_applied");
        adc.setPowerMode(ADS7828Full::REF_ON_ADC_ON);

        // powerDown: lone 0x00; next read re-enables the reference first.
        adc.powerDown();
        check_true(lastIs(mock, 0x00), "power_down_command");
        adc.readRaw(0);
        check_true(nthLastIs(mock, 1, 0x08), "read_after_power_down_reenables_reference");
    }

    // External reference: no init traffic, PD=00, scaling by vref.
    {
        I2CConnectionMock mock;
        ADS7828Full adc(mock, 5.0f);
        check_true(mock.writes().empty(), "external_no_init_traffic");
        check_true(adc.getReference() == 5.0f && !adc.usesInternalReference(), "external_reference");
        mock.setRegister(0x80, {0x08, 0x00});
        check_true(close(adc.readVoltage(0), 2.5f), "external_read_voltage");
        check_true(lastIs(mock, 0x80), "external_command_pd0");
        check_true(adc.setPowerMode(ADS7828Full::POWER_DOWN_BETWEEN), "external_allows_pd0");

        adc.setReference(0.0f);
        check_true(lastIs(mock, 0x08) && adc.getReference() == 2.5f, "set_reference_internal_enables");
        adc.setReference(3.3f);
        check_true(adc.getReference() == 3.3f && !adc.usesInternalReference(), "set_reference_external");
        adc.readRaw(0);
        check_true(lastIs(mock, 0x80), "set_reference_external_pd0");
    }

    // Minimal stands alone.
    {
        I2CConnectionMock mock;
        ADS7828Minimal adc(mock);
        check_true(adc.readRaw(0) == 0, "minimal_class");
        check_true(mock.writes().size() == 2 && mock.writes()[0][0] == 0x08 && mock.writes()[1][0] == 0x8C, "first_read_enables_reference_lazily");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
