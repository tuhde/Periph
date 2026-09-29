#include <stdio.h>
#include "HX711ConnectionMock.h"
#include "HX710B.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    // --- HX710BMinimal ---
    {
        HX711ConnectionMock connection;
        connection.queueRead(0);  // discarded by construction
        HX710BMinimal<HX711ConnectionMock> sensor(connection);
        check_true(connection.reads().size() == 1 && connection.reads()[0] == 25,
                   "init_discards_first_reading");

        connection.ready = false;
        check_true(sensor.is_ready() == false, "is_ready_false");
        connection.ready = true;
        check_true(sensor.is_ready() == true, "is_ready_true");

        connection.queueRead(12345);
        check_true(sensor.read_raw() == 12345, "read_raw_10sps");
        check_true(connection.reads().back() == 25, "read_raw_uses_25_pulses");
    }

    // --- HX710BFull ---
    HX711ConnectionMock connection;
    connection.queueRead(0);
    HX710BFull<HX711ConnectionMock> sensor(connection);
    check_true(connection.reads().size() == 1 && connection.reads()[0] == 25,
               "full_init_discards_first_reading");

    connection.queueRead(1000);
    check_true(sensor.read_raw() == 1000, "full_read_raw_default_10sps");
    check_true(connection.reads().back() == 25, "full_read_raw_default_25_pulses");

    // set_rate(): selects pulse count and issues one dummy read to apply it.
    connection.queueRead(0);  // dummy read issued by set_rate(40)
    sensor.set_rate(40);
    check_true(connection.reads().back() == 27, "set_rate_40_issues_dummy_read");
    connection.queueRead(2000);
    sensor.read_raw();
    check_true(connection.reads().back() == 27, "set_rate_40_pulses");

    connection.queueRead(0);  // dummy read issued by set_rate(10)
    sensor.set_rate(10);
    check_true(connection.reads().back() == 25, "set_rate_10_issues_dummy_read");

    // set_rate(invalid): silently no-ops, leaving pulse count unchanged
    // (matches HX711's set_gain() convention — no exception).
    size_t readsBeforeInvalid = connection.reads().size();
    sensor.set_rate(99);
    check_true(connection.reads().size() == readsBeforeInvalid,
               "set_rate_invalid_issues_no_read");
    connection.queueRead(5555);
    sensor.read_raw();
    check_true(connection.reads().back() == 25, "set_rate_invalid_leaves_pulses_unchanged");

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

    // Regression: read_supply_diff_raw() must clock exactly 26 pulses (the
    // DVDD-AVDD channel per the HX710B pulse-count table), not 25 or 27
    // (which would silently read the differential input instead).
    connection.queueRead(777);
    check_true(sensor.read_supply_diff_raw() == 777, "read_supply_diff_raw_value");
    check_true(connection.reads().back() == 26, "read_supply_diff_raw_uses_26_pulses");

    // power_down()/power_up(): power_up() resets the rate to 25 pulses and
    // discards one reading, even if a non-default rate was previously
    // selected.
    connection.queueRead(0);  // dummy read issued by set_rate(40)
    sensor.set_rate(40);
    sensor.power_down();
    check_true(connection.powerCalls().back() == "down", "power_down_calls_connection");
    connection.queueRead(0);  // discarded by power_up()
    sensor.power_up();
    check_true(connection.powerCalls().back() == "up", "power_up_calls_connection");
    check_true(connection.reads().back() == 25, "power_up_resets_rate");
    connection.queueRead(4242);
    sensor.read_raw();
    check_true(connection.reads().back() == 25, "power_up_read_raw_uses_25_pulses");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
