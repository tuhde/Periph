#include <stdio.h>
#include "HX711ConnectionMock.h"
#include "HX711.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    // --- HX711Minimal ---
    {
        HX711ConnectionMock connection;
        connection.queueRead(0);  // discarded by construction
        HX711Minimal<HX711ConnectionMock> sensor(connection);
        check_true(connection.reads().size() == 1 && connection.reads()[0] == 25,
                   "init_discards_first_reading");

        connection.ready = false;
        check_true(sensor.is_ready() == false, "is_ready_false");
        connection.ready = true;
        check_true(sensor.is_ready() == true, "is_ready_true");

        connection.queueRead(12345);
        check_true(sensor.read_raw() == 12345, "read_raw_gain_128");
        check_true(connection.reads().back() == 25, "read_raw_uses_25_pulses");
    }

    // --- HX711Full ---
    HX711ConnectionMock connection;
    connection.queueRead(0);
    HX711Full<HX711ConnectionMock> sensor(connection);
    check_true(connection.reads().size() == 1 && connection.reads()[0] == 25,
               "full_init_discards_first_reading");

    connection.queueRead(1000);
    check_true(sensor.read_raw() == 1000, "full_read_raw_default_gain_128");
    check_true(connection.reads().back() == 25, "full_read_raw_default_25_pulses");

    // set_gain(): selects pulse count and issues one dummy read to apply it.
    connection.queueRead(0);  // dummy read issued by set_gain(64)
    sensor.set_gain(64);
    check_true(connection.reads().back() == 27, "set_gain_64_issues_dummy_read");
    connection.queueRead(2000);
    sensor.read_raw();
    check_true(connection.reads().back() == 27, "set_gain_64_pulses");

    connection.queueRead(0);  // dummy read issued by set_gain(32)
    sensor.set_gain(32);
    check_true(connection.reads().back() == 26, "set_gain_32_issues_dummy_read");
    connection.queueRead(3000);
    sensor.read_raw();
    check_true(connection.reads().back() == 26, "set_gain_32_pulses");

    connection.queueRead(0);  // dummy read issued by set_gain(128)
    sensor.set_gain(128);
    check_true(connection.reads().back() == 25, "set_gain_128_issues_dummy_read");

    // set_gain(invalid): silently no-ops, leaving pulse count unchanged
    // (existing, deliberate driver behavior — no exception).
    size_t readsBeforeInvalid = connection.reads().size();
    sensor.set_gain(99);
    check_true(connection.reads().size() == readsBeforeInvalid,
               "set_gain_invalid_issues_no_read");
    connection.queueRead(5555);
    sensor.read_raw();
    check_true(connection.reads().back() == 25, "set_gain_invalid_leaves_pulses_unchanged");

    // read_average(): mean of `times` raw readings, integer division.
    connection.queueRead(10);
    connection.queueRead(20);
    connection.queueRead(33);
    check_true(sensor.read_average(3) == (10 + 20 + 33) / 3, "read_average");

    // tare(): captures read_average() as the offset.
    connection.queueRead(100);
    connection.queueRead(100);
    sensor.tare(2);
    check_true(sensor.get_offset() == 100, "tare_sets_offset");

    // set_scale()/get_scale().
    sensor.set_scale(2.5f);
    check_true(sensor.get_scale() == 2.5f, "set_scale");

    // read_weight(): (read_average(times) - offset) / scale.
    connection.queueRead(350);
    check_true(sensor.read_weight(1) == (350.0f - 100.0f) / 2.5f, "read_weight");

    // power_down()/power_up(): power_up() resets pulse count to 25 and
    // discards one reading, even if a non-default gain was previously
    // selected.
    connection.queueRead(0);  // dummy read issued by set_gain(64)
    sensor.set_gain(64);
    sensor.power_down();
    check_true(connection.powerCalls().back() == "down", "power_down_calls_connection");
    connection.queueRead(0);  // discarded by power_up()
    sensor.power_up();
    check_true(connection.powerCalls().back() == "up", "power_up_calls_connection");
    check_true(connection.reads().back() == 25, "power_up_resets_gain");
    connection.queueRead(4242);
    sensor.read_raw();
    check_true(connection.reads().back() == 25, "power_up_read_raw_uses_25_pulses");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
