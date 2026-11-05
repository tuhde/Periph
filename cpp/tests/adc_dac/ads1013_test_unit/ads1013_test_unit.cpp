#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "ADS1013.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Word-addressed variant of the byte-slot mock: 16-bit registers at
// consecutive pointer values would overlap in the shared mock's byte-slot
// model. Each pointer owns one 16-bit word here.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, uint16_t> words;
    std::vector<std::vector<uint8_t>> log;

    WordMock() { words[0] = 0x0000; words[1] = 0x8583; }

    uint16_t lastConfigWrite() const {
        uint16_t last = 0;
        for (const auto& w : log) if (w.size() == 3 && w[0] == 1) last = (uint16_t)((w[1] << 8) | w[2]);
        return last;
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

int main() {
    {
        WordMock mock;
        ADS1013Minimal adc(mock);
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

    {
        WordMock mock;
        ADS1013Full adc(mock);
        check_true(adc.fullScale() == 2.048f, "full_scale");
        check_true(adc.lsb() == 0.001f, "lsb");
        adc.setDataRate(ADS1013Full::DR_128);
        check_true(adc.dataRateSps() == 128 && adc.getDataRate() == 0, "dr_sps_128");
        adc.setDataRate(ADS1013Full::DR_3300);
        check_true(adc.dataRateSps() == 3300 && adc.getDataRate() == 6, "dr_sps_3300");
        mock.words[0] = 0x1000;
        adc.readRaw();
        check_true(mock.lastConfigWrite() == (0x8000 | 0x0403 | 0x0100 | (6 << 5)), "config_word_dr");
        check_true(!adc.setDataRate(7), "dr_rejects_7");
    }

    {
        WordMock mock;
        ADS1013Full adc(mock);
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
        adc.setDataRate(ADS1013Full::DR_128);
        adc.reset();
        check_true(mock.words[1] == 0x8583 && adc.getDataRate() == 4, "reset_config");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
