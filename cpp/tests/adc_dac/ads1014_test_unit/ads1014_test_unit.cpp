#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "ADS1014.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Word-addressed variant of the byte-slot mock: the ADS1014 registers are 16
// bits wide at consecutive pointer values, which would overlap in the shared
// mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, uint16_t> words;
    std::vector<std::vector<uint8_t>> log;

    WordMock() { words[0] = 0x0000; words[1] = 0x8583; words[2] = 0x8000; words[3] = 0x7FFF; }

    uint16_t lastConfigWrite() const {
        uint16_t last = 0;
        for (const auto& w : log) if (w.size() == 3 && w[0] == 1) last = (uint16_t)((w[1] << 8) | w[2]);
        return last;
    }

    bool pointerWritten(uint8_t reg) const {
        for (const auto& w : log) if (w.size() == 1 && w[0] == reg) return true;
        return false;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        if (len == 3) words[data[0]] = (uint16_t)((data[1] << 8) | data[2]);
    }

    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        log.emplace_back(data, data + data_len);
        uint16_t word = words.count(data[0]) ? words[data[0]] : 0;
        buf[0] = (uint8_t)(word >> 8);
        buf[1] = (uint8_t)(word & 0xFF);
        (void)buf_len;
    }
};

// Fake INT pin: level readable, edge handler recorded.
class FakePin : public InputPin {
public:
    bool high = true;
    Handler handler = nullptr;
    uint8_t trigger = 0;
    bool onEdge(Handler h, uint8_t t = kFalling) override { handler = h; trigger = t; return true; }
    void offEdge(Handler h) override { if (handler == h) handler = nullptr; }
    bool isHigh() override { return high; }
};

static uint8_t lastStatus = 0;
static void onAlert(uint8_t status) { lastStatus = status; }

int main() {
    // Minimal: init and single-shot.
    {
        WordMock mock;
        ADS1014Minimal adc(mock);
        check_true(mock.log.empty(), "init_no_traffic");
        mock.words[0] = 0x7FF0;
        check_true(adc.readRaw() == 2047, "read_raw_max");
        check_true(mock.lastConfigWrite() == 0x8583, "config_word_default");
        mock.words[0] = 0x8000;
        check_true(adc.readRaw() == -2048, "read_raw_min");
        mock.words[0] = 0xFFF0;
        check_true(adc.readRaw() == -1, "read_raw_minus_one");
        mock.words[0] = 0x3E80;
        float v = adc.readVoltage() - 1.0f;
        check_true(v < 1e-5f && v > -1e-5f, "read_voltage_1v");
    }

    // Full: gain and data rate.
    {
        WordMock mock;
        ADS1014Full adc(mock);
        check_true(adc.setGain(ADS1014Full::PGA_6_144), "gain_set");
        check_true(adc.getGain() == 0, "gain_get");
        check_true(adc.fullScale() == 6.144f, "full_scale_6_144");
        float l = adc.lsb() - 0.003f;
        check_true(l < 1e-6f && l > -1e-6f, "lsb_6_144");
        adc.setGain(ADS1014Full::PGA_0_256);
        mock.words[0] = 0x4000;
        float v = adc.readVoltage() - 0.256f * 0x400 / 2048.0f;
        check_true(v < 1e-6f && v > -1e-6f, "read_voltage_gain");
        check_true(mock.lastConfigWrite() == (0x8000 | (5 << 9) | 0x0100 | (4 << 5) | 0x3), "config_gain_bits");
        check_true(!adc.setGain(6) && !adc.setGain(7), "gain_rejects_6_7");
        check_true(adc.getGain() == 5, "gain_unchanged_after_reject");

        adc.setDataRate(ADS1014Full::DR_128);
        check_true(adc.dataRateSps() == 128, "dr_sps_128");
        adc.setDataRate(ADS1014Full::DR_3300);
        check_true(adc.dataRateSps() == 3300 && adc.getDataRate() == 6, "dr_sps_3300");
        check_true(!adc.setDataRate(7), "dr_rejects_7");
    }

    // Continuous mode.
    {
        WordMock mock;
        ADS1014Full adc(mock);
        adc.startContinuous();
        check_true(mock.lastConfigWrite() == 0x0483, "continuous_config");
        mock.words[0] = 0x1000;
        mock.log.clear();
        check_true(adc.readContinuousRaw() == 256, "continuous_raw");
        bool anyConfigWrite = false;
        for (const auto& w : mock.log) if (w.size() == 3) anyConfigWrite = true;
        check_true(!anyConfigWrite, "continuous_no_config_write");
        float v = adc.readContinuous() - 0.256f;
        check_true(v < 1e-5f && v > -1e-5f, "continuous_volts");
        adc.stopContinuous();
        check_true(mock.lastConfigWrite() == 0x0583, "stop_continuous_config");
        mock.words[1] = 0x0583;
        check_true(adc.isConverting(), "is_converting_true");
        mock.words[1] = 0x8583;
        check_true(!adc.isConverting(), "is_converting_false");
        check_true(adc.readConfig() == 0x8583, "read_config");
    }

    // Comparator and thresholds.
    {
        WordMock mock;
        ADS1014Full adc(mock);
        check_true(adc.configureComparator(ADS1014Full::COMP_WINDOW, ADS1014Full::ACTIVE_HIGH, true,
                                           ADS1014Full::QUEUE_4), "comparator_configure");
        check_true(mock.lastConfigWrite() == ((0x0583 & ~0x3F) | 0x10 | 0x08 | 0x04 | 0x02), "comparator_config");
        adc.disableComparator();
        check_true((mock.lastConfigWrite() & 0x3) == 3, "comparator_disabled");
        check_true(!adc.configureComparator(0, 0, false, 3), "queue_rejects_3");

        check_true(adc.setThresholds(-0.5f, 1.0f), "thresholds_set");
        check_true(mock.words[2] == (uint16_t)((-500 & 0xFFF) << 4), "threshold_low_word");
        check_true(mock.words[3] == (uint16_t)(1000 << 4), "threshold_high_word");
        int16_t lo = 0, hi = 0;
        adc.getThresholdsRaw(&lo, &hi);
        check_true(lo == -500 && hi == 1000, "threshold_readback");
        adc.setThresholds(-10.0f, 10.0f);
        adc.getThresholdsRaw(&lo, &hi);
        check_true(lo == -2048 && hi == 2047, "threshold_clamp");
        check_true(!adc.setThresholds(1.0f, 1.0f), "threshold_rejects_equal");
        check_true(!adc.setHighThresholdRaw(2048), "threshold_raw_rejects_range");

        mock.log.clear();
        adc.clearAlert();
        check_true(mock.log.size() == 1 && mock.log[0].size() == 1 && mock.log[0][0] == 0, "clear_alert_reads_conversion");

        adc.reset();
        check_true(mock.words[1] == 0x8583, "reset_config");
        check_true(mock.words[2] == 0x8000 && mock.words[3] == 0x7FFF, "reset_thresholds");
    }

    // Interrupt routing.
    {
        WordMock mock;
        FakePin pin;
        ADS1014Full adc(mock);
        adc.onInterrupt(onAlert, &pin);
        check_true(pin.handler != nullptr && pin.trigger == InputPin::kFalling, "on_interrupt_registers_edge");
        adc.enableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);
        check_true(mock.words[3] == 0x8000 && mock.words[2] == 0x0000, "ready_thresholds");
        check_true((mock.words[1] & 0x3) == 0, "ready_que_00");
        check_true(adc.pollInterrupt() == 0, "poll_idle");
        pin.high = false;
        check_true(adc.pollInterrupt() == ADS1014Full::SOURCE_CONVERSION_READY, "poll_ready");

        adc.setHighThresholdRaw(-5);
        adc.setLowThresholdRaw(10);
        check_true(!adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR), "comparator_needs_thresholds");
        adc.setThresholds(-0.1f, 0.1f);
        adc.configureComparator(ADS1014Full::COMP_TRADITIONAL, ADS1014Full::ACTIVE_LOW, true);
        check_true(adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR), "comparator_enable");
        int16_t lo = 0, hi = 0;
        adc.getThresholdsRaw(&lo, &hi);
        check_true(lo == -100 && hi == 100, "comparator_thresholds_restored");
        mock.log.clear();
        check_true(adc.pollInterrupt() == ADS1014Full::SOURCE_COMPARATOR, "poll_comparator");
        check_true(mock.pointerWritten(0), "poll_latched_reads_conversion");

        adc.disableInterrupt(ADS1014Full::SOURCE_COMPARATOR);
        check_true((mock.words[1] & 0x3) == 3, "disable_que_11");
        check_true(adc.pollInterrupt() == 0, "poll_disabled");

        adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR);
        pin.handler();
        check_true(lastStatus == ADS1014Full::SOURCE_COMPARATOR, "on_interrupt_callback");
        adc.offInterrupt();
        check_true(pin.handler == nullptr, "off_interrupt");
    }

    // No pin: conversion-ready falls back to CONFIG.OS, comparator reads 0.
    {
        WordMock mock;
        ADS1014Full adc(mock);
        adc.enableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);
        mock.words[1] |= 0x8000;  // the real chip reads OS = 1 when idle
        check_true(adc.pollInterrupt() == ADS1014Full::SOURCE_CONVERSION_READY, "poll_fallback_ready");
        mock.words[1] &= 0x7FFF;
        check_true(adc.pollInterrupt() == 0, "poll_fallback_busy");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
