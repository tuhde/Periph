#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "MCP9808.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Word-addressed variant of the byte-slot mock: MCP9808 registers are 16 bits
// wide at consecutive pointer values (MANUFACTURER_ID at 0x06, DEVICE_ID at
// 0x07), which would overlap in the shared mock's byte-slot model. Each
// pointer owns one 16-bit word here; 1-byte accesses (RESOLUTION) use the
// word's low byte. Writes are still logged through the base class.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, uint16_t> words;
    std::vector<std::vector<uint8_t>> log;

    WordMock() { words[0x06] = 0x0054; words[0x07] = 0x0400; words[0x08] = 0x0003; }

    int writesTo(uint8_t reg) const {
        int n = 0;
        for (const auto& w : log) if (w.size() >= 2 && w[0] == reg) n++;
        return n;
    }

    std::vector<uint8_t> lastWriteTo(uint8_t reg) const {
        std::vector<uint8_t> last;
        for (const auto& w : log) if (w.size() >= 2 && w[0] == reg) last = w;
        return last;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        if (len == 3) words[data[0]] = (uint16_t)((data[1] << 8) | data[2]);
        else if (len == 2) words[data[0]] = data[1];
    }

    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        log.emplace_back(data, data + data_len);
        uint16_t word = words.count(data[0]) ? words[data[0]] : 0;
        if (buf_len == 1) { buf[0] = (uint8_t)(word & 0xFF); return; }
        buf[0] = (uint8_t)(word >> 8);
        buf[1] = (uint8_t)(word & 0xFF);
    }
};

static const uint8_t REG_CONFIG = 0x01;
static const uint8_t REG_TA     = 0x05;

int main() {
    // Identity check passes and writes nothing. (A mismatch calls abort(),
    // which a single-process unit test cannot observe without dying.)
    {
        WordMock mock;
        MCP9808Minimal sensor(mock);
        bool onlyPointerWrites = true;
        for (const auto& w : mock.log) if (w.size() != 1) onlyPointerWrites = false;
        check_true(onlyPointerWrites, "init_no_register_writes");

        WordMock rev;
        rev.words[0x07] = 0x0401;  // revision byte is ignored
        MCP9808Minimal withRevision(rev);
        check_true(true, "init_ignores_revision");

        mock.words[REG_TA] = 0x0194;
        check_true(sensor.readTemperature() == 25.25f, "temperature_positive");
        mock.words[REG_TA] = 0xE194;
        check_true(sensor.readTemperature() == 25.25f, "temperature_masks_flags");
        mock.words[REG_TA] = 0x1FF0;
        check_true(sensor.readTemperature() == -1.0f, "temperature_negative");
        mock.words[REG_TA] = 0x1E6C;
        check_true(sensor.readTemperature() == -25.25f, "temperature_negative_fraction");
        mock.words[REG_TA] = 0x0001;
        check_true(sensor.readTemperature() == 0.0625f, "temperature_lsb");
    }

    {
        WordMock mock;
        MCP9808Full full(mock);

        // Boundaries.
        full.setUpperLimit(80.0f);
        check_true(mock.words[0x02] == 0x0500, "upper_limit_encode");
        check_true(full.getUpperLimit() == 80.0f, "upper_limit_decode");
        full.setLowerLimit(-25.0f);
        check_true(mock.words[0x03] == 0x1E70, "lower_limit_encode");
        check_true(full.getLowerLimit() == -25.0f, "lower_limit_decode");
        full.setCriticalLimit(-5.1f);
        check_true(full.getCriticalLimit() == -5.0f, "critical_limit_rounds");
        full.setCriticalLimit(22.13f);
        check_true(full.getCriticalLimit() == 22.25f, "critical_limit_rounds_up");
        full.setUpperLimit(1000.0f);
        check_true(full.getUpperLimit() == 255.75f, "limit_clamps_high");
        full.setLowerLimit(-1000.0f);
        check_true(full.getLowerLimit() == -256.0f, "limit_clamps_low");

        // Resolution.
        check_true(full.setResolution(0.25f), "resolution_write_ok");
        std::vector<uint8_t> res = mock.lastWriteTo(0x08);
        check_true(res.size() == 2 && res[1] == 0x01, "resolution_write");
        check_true(full.getResolution() == 0.25f, "resolution_read");
        int before = mock.writesTo(0x08);
        check_true(!full.setResolution(0.3f), "resolution_rejects_invalid");
        check_true(mock.writesTo(0x08) == before, "resolution_invalid_no_write");

        // Hysteresis.
        mock.words[REG_CONFIG] = 0x0000;
        check_true(full.setHysteresis(3.0f), "hysteresis_write_ok");
        check_true(mock.words[REG_CONFIG] == 0x0400, "hysteresis_write");
        check_true(full.getHysteresis() == 3.0f, "hysteresis_read");
        check_true(!full.setHysteresis(2.0f), "hysteresis_rejects_invalid");

        // Shutdown / wake.
        mock.words[REG_CONFIG] = 0x0400;
        full.shutdown();
        check_true(mock.words[REG_CONFIG] == 0x0500, "shutdown_sets_shdn_keeps_thyst");
        check_true(full.isShutdown(), "is_shutdown_true");
        full.wake();
        check_true(mock.words[REG_CONFIG] == 0x0400, "wake_clears_shdn");
        check_true(!full.isShutdown(), "is_shutdown_false");
        mock.words[REG_CONFIG] = 0x0080;
        before = mock.writesTo(REG_CONFIG);
        full.shutdown();
        check_true(mock.writesTo(REG_CONFIG) == before, "shutdown_noop_when_locked");

        // Locks.
        mock.words[REG_CONFIG] = 0x0000;
        full.lockCriticalLimit();
        check_true(mock.words[REG_CONFIG] == 0x0080, "lock_critical_sets_bit");
        check_true(full.isCriticalLimitLocked(), "is_critical_locked");
        check_true(!full.isWindowLimitsLocked(), "is_window_unlocked");
        mock.words[REG_CONFIG] = 0x0000;
        full.lockWindowLimits();
        check_true(mock.words[REG_CONFIG] == 0x0040, "lock_window_sets_bit");
        check_true(full.isWindowLimitsLocked(), "is_window_locked");

        // Alert configuration.
        mock.words[REG_CONFIG] = 0x0000;
        check_true(full.configureAlert(MCP9808Full::AlertMode::CriticalOnly,
                                       MCP9808Full::AlertOutput::Interrupt,
                                       MCP9808Full::AlertPolarity::ActiveHigh), "configure_alert_ok");
        check_true(mock.words[REG_CONFIG] == 0x0007, "configure_alert_bits");
        full.configureAlert();
        check_true(mock.words[REG_CONFIG] == 0x0000, "configure_alert_defaults");
        mock.words[REG_CONFIG] = 0x0040;
        check_true(!full.configureAlert(MCP9808Full::AlertMode::All,
                                        MCP9808Full::AlertOutput::Interrupt),
                   "configure_alert_rejects_locked");
        check_true(mock.words[REG_CONFIG] == 0x0040, "configure_alert_locked_no_write");

        mock.words[REG_CONFIG] = 0x0000;
        full.enableAlert();
        check_true(mock.words[REG_CONFIG] == 0x0008, "enable_alert");
        full.disableAlert();
        check_true(mock.words[REG_CONFIG] == 0x0000, "disable_alert");

        mock.words[REG_CONFIG] = 0x0019;  // ALERT_STAT | ALERT_CNT | ALERT_MOD
        check_true(full.isAlertAsserted(), "is_alert_asserted");
        full.clearInterrupt();
        std::vector<uint8_t> clr = mock.lastWriteTo(REG_CONFIG);
        check_true(clr.size() == 3 && clr[1] == 0x00 && clr[2] == 0x29, "clear_interrupt_write");
        mock.words[REG_CONFIG] = 0x0009;
        check_true(!full.isAlertAsserted(), "is_alert_not_asserted");

        // pollInterrupt.
        mock.words[REG_TA] = 0x0194;
        check_true(full.pollInterrupt() == 0, "poll_interrupt_none");
        mock.words[REG_TA] = 0x2194;
        check_true(full.pollInterrupt() == MCP9808Full::SOURCE_LOWER, "poll_interrupt_lower");
        mock.words[REG_TA] = 0xC194;
        check_true(full.pollInterrupt() == (MCP9808Full::SOURCE_UPPER | MCP9808Full::SOURCE_CRITICAL),
                   "poll_interrupt_upper_critical");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
