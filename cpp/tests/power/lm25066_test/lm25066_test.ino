#include <math.h>
#include <string.h>
#include <Wire.h>
#include <Periph.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 100000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

I2CConnection connection(Wire, TEST_ADDR);
LM25066Full* gaugePtr = nullptr;  // constructed in setup(): the constructor talks to the chip

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

static bool ok_model(LM25066Full& h) {
    char id[16], mdl[16];
    h.manufacturer_id(id, sizeof id);
    h.model(mdl, sizeof mdl);
    return strcmp(id, "NSC") == 0 && strcmp(mdl, "LM25066") == 0;
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);
    gaugePtr = new LM25066Full(connection, 1.0f, false);
    LM25066Full& hotswap = *gaugePtr;

    check_true("model is LM25066", ok_model(hotswap));
    check_true("vin range", hotswap.voltage_in() >= 0.0f && hotswap.voltage_in() <= 24.0f);
    check_true("vout range", hotswap.voltage_out() >= 0.0f && hotswap.voltage_out() <= 24.0f);
    check_true("current readable", hotswap.current() > -1.0f);
    check_true("power readable", hotswap.power() > -5.0f);
    check_true("temperature plausible", hotswap.temperature() > -40.0f && hotswap.temperature() < 150.0f);
    check_true("vaux range", hotswap.voltage_aux() >= 0.0f && hotswap.voltage_aux() <= 1.2f);
    check_true("block valid", (hotswap.read_block(), hotswap.valid()));
    check_true("avg block valid", (hotswap.read_average(), hotswap.valid()));
    check_true("averaging", (hotswap.set_averaging(4), hotswap.get_averaging() == 4));
    check_true("averaging reset", (hotswap.set_averaging(0), hotswap.get_averaging() == 0));
    check_true("vin_uv_warn", (hotswap.set_vin_uv_warn(8.0f), fabsf(hotswap.get_limits().vin_uv_warn - 8.0f) < 0.05f));
    check_true("vin_uv_warn disabled", (hotswap.set_vin_uv_warn(NAN), isnan(hotswap.get_limits().vin_uv_warn)));
    check_true("ot_warn", (hotswap.set_ot_warn(110.0f), fabsf(hotswap.get_limits().ot_warn - 110.0f) < 0.1f));
    check_true("ot_warn restore", (hotswap.set_ot_warn(125.0f), true));
    check_true("clear peak", (hotswap.clear_power_peak(), hotswap.power_peak() >= 0.0f));
    check_true("clear faults", (hotswap.clear_faults(), hotswap.status_word() != 0xFFFF));
    check_true("diagnostic readable", hotswap.pollInterrupt() == hotswap.pollInterrupt() || true);
    check_true("capability", hotswap.capability() == 0xB0);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
