#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "ADS1115.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Word-addressed variant of the byte-slot mock: the four ADS111x registers are
// 16 bits wide at consecutive pointer values, which would overlap in the
// shared mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, uint16_t> words;
    std::vector<std::vector<uint8_t>> log;

    WordMock() {
        words[0] = 0x0000; words[1] = 0x8583; words[2] = 0x8000; words[3] = 0x7FFF;
    }

    uint16_t lastConfig() const {
        uint16_t last = 0;
        for (const auto& w : log) if (w.size() == 3 && w[0] == 1) last = (uint16_t)((w[1] << 8) | w[2]);
        return last;
    }
    size_t configWrites() const {
        size_t n = 0;
        for (const auto& w : log) if (w.size() == 3 && w[0] == 1) n++;
        return n;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        if (len == 3) words[data[0]] = (uint16_t)((data[1] << 8) | data[2]);
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        log.emplace_back(data, data + data_len);
        uint16_t word = words.count(data[0]) ? words[data[0]] : 0;
        buf[0] = (uint8_t)(word >> 8);
        if (buf_len > 1) buf[1] = (uint8_t)(word & 0xFF);
    }
};

class FakePin : public InputPin {
public:
    bool high;
    explicit FakePin(bool h) : high(h) {}
    bool onEdge(Handler, uint8_t) override { return true; }
    void offEdge(Handler) override {}
    bool isHigh() override { return high; }
};

int main() {
    {
        WordMock mock;
        ADS1115Minimal adc(mock);
        check_true(mock.log.empty(), "init_no_traffic");
        mock.words[0] = 0x4000;
        check_true(adc.readRaw() == 0x4000, "read_raw_value");
        check_true(mock.lastConfig() == 0xC583, "read_raw_config_word");
        mock.words[0] = 0xFFFF;
        check_true(adc.readRaw(2) == -1, "read_raw_negative");
        check_true(mock.lastConfig() == 0xE583, "read_raw_ch2_mux");
        adc.readRaw(9);
        check_true(mock.lastConfig() == 0xF583, "read_raw_clamps_channel");
        mock.words[0] = 0x4000;
        check_true(adc.readVoltage() == 0x4000 * 0.0000625f, "read_voltage");
    }
    {
        WordMock mock;
        ADS1115Full full(mock);
        full.setGain(ADS1115Full::PGA_4_096);
        check_true(full.fullScale() == 4.096f && full.lsb() == 4.096f / 32768.0f, "gain_scale");
        mock.words[0] = 0x2000;
        check_true(full.readVoltage(1) == 0x2000 * (4.096f / 32768.0f), "read_voltage_uses_gain");
        check_true(mock.lastConfig() == 0xD383, "gain_in_config");
        full.setGain(6);
        check_true(full.getGain() == 1, "gain_rejects_6");
        full.setDataRate(ADS1115Full::DR_860);
        check_true(full.dataRateSps() == 860 && full.getDataRate() == 7, "data_rate_sps");
        full.readRaw();
        check_true(mock.lastConfig() == 0xC3E3, "data_rate_in_config");
        check_true(full.readDifferentialRaw(ADS1115Full::DIFF_2_3) == 0x2000, "differential_raw");
        check_true(mock.lastConfig() == 0xB3E3, "differential_mux");
        full.startContinuous(ADS1115Full::MUX_AIN1_GND);
        check_true(mock.lastConfig() == 0x52E3, "continuous_config");
        mock.words[0] = 0x0100;
        check_true(full.readContinuousRaw() == 256, "continuous_read");
        full.stopContinuous();
        check_true(mock.lastConfig() == 0x53E3, "stop_continuous");
        mock.words[1] = 0x0583;
        check_true(full.isConverting(), "is_converting");
        mock.words[1] = 0x8583;
        check_true(!full.isConverting(), "not_converting");
        check_true(full.readConfig() == 0x8583, "read_config");
    }
    {
        WordMock mock;
        ADS1115Full full(mock);
        full.setThresholds(0.5f, 3.0f);
        check_true(mock.words[2] == 8000, "thresholds_raw_low");
        check_true(mock.words[3] == 0x7FFF, "thresholds_raw_high_clamped");
        full.setHighThresholdRaw(-100);
        check_true(mock.words[3] == 0xFF9C, "negative_threshold_encoding");
        full.setLowThresholdRaw(-200);
        int16_t lo = 0, hi = 0;
        full.getThresholdsRaw(&lo, &hi);
        check_true(lo == -200 && hi == -100, "get_thresholds_raw");
        full.setThresholds(1.0f, 1.0f);
        check_true(mock.words[2] == 0xFF38, "thresholds_reject_inverted");
        full.setThresholds(0.5f, 1.5f);
        full.configureComparator(ADS1115Full::COMP_WINDOW, ADS1115Full::ACTIVE_HIGH, true, ADS1115Full::QUEUE_2);
        check_true(mock.lastConfig() == (0x0400 | 0x0100 | 0x0080 | 0x0010 | 0x0008 | 0x0004 | 0x0001), "comparator_bits");
        full.disableComparator();
        check_true((mock.lastConfig() & 3) == 3, "disable_comparator");
        mock.log.clear();
        full.clearAlert();
        check_true(mock.log.size() == 1 && mock.log[0][0] == 0, "clear_alert_reads_conversion");
    }
    {
        WordMock mock;
        FakePin pin(true);
        ADS1115Full full(mock);
        full.enableInterrupt(ADS1115Full::SOURCE_CONVERSION_READY);
        check_true(mock.words[3] == 0x8000 && mock.words[2] == 0x0000, "ready_thresholds");
        check_true((mock.lastConfig() & 3) == 0, "ready_queue");
        mock.words[1] = 0x8583;
        check_true(full.pollInterrupt() == ADS1115Full::SOURCE_CONVERSION_READY, "poll_ready_fallback");
        mock.words[1] = 0x0583;
        check_true(full.pollInterrupt() == 0, "poll_ready_idle");
        full.onInterrupt(nullptr, &pin);
        pin.high = false;
        check_true(full.pollInterrupt() == ADS1115Full::SOURCE_CONVERSION_READY, "poll_pin_asserted_low");
        pin.high = true;
        check_true(full.pollInterrupt() == 0, "poll_pin_deasserted");
        full.disableInterrupt(ADS1115Full::SOURCE_CONVERSION_READY);
        check_true((mock.lastConfig() & 3) == 3, "disable_interrupt");
        check_true(full.pollInterrupt() == 0, "poll_disabled");
        full.setThresholds(0.5f, 1.5f);
        full.enableInterrupt(ADS1115Full::SOURCE_COMPARATOR);
        check_true((mock.lastConfig() & 3) == 0, "enable_comparator");
        pin.high = false;
        check_true(full.pollInterrupt() == ADS1115Full::SOURCE_COMPARATOR, "poll_comparator_asserted");
        full.setGain(0);
        full.reset();
        check_true(mock.words[1] == 0x8583 && mock.words[2] == 0x8000 && mock.words[3] == 0x7FFF, "reset_writes");
        check_true(full.getGain() == 2 && full.getDataRate() == 4, "reset_clears_cache");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
