#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x34
#endif

#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, TEST_ADDR);

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); passed++; }
    else      { Serial.print("FAIL "); failed++; }
    Serial.println(label);
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    AXP2101Minimal chip(connection);                       // Create AXP2101 driver, (connection)
    AXP2101Full full(connection);                          // Create AXP2101 Full driver, (connection)

    float v = chip.batteryVoltage();
    check_true(v >= 0.0f && v <= 5.0f, "battery_voltage_plausible");
    check_true(chip.batteryPercent() <= 100, "battery_percent_range");
    check_true(chip.chargeState() <= 5, "charge_state_range");
    check_true(chip.valid(), "bus_check_ok");
    check_true(full.valid(), "construct_full");
    check_true(full.vbusVoltage() >= 0.0f && full.vbusVoltage() <= 15.0f, "vbus_voltage_range");
    check_true(full.systemVoltage() >= 0.0f && full.systemVoltage() <= 6.0f, "system_voltage_range");
    check_true(full.tsVoltage() >= 0.0f && full.tsVoltage() <= 4.0f, "ts_voltage_range");
    float t = full.dieTemperature();
    check_true(t >= -40.0f && t <= 150.0f, "die_temperature_range");
    AXP2101Readings all;
    full.readAll(all);
    check_true(all.batteryPercent <= 100, "read_all");
    AXP2101Status st;
    full.status(st);
    check_true(st.batteryDirection != nullptr, "status_direction");
    bool en = false;
    float volts = 0.0f;
    check_true(full.getRail("dcdc1", en, volts) && volts >= 1.4f && volts <= 3.5f, "get_rail_dcdc1");
    check_true(full.setInputVoltageLimit(4.36f), "set_input_voltage_limit");
    uint8_t b = 0;
    check_true(full.writeDataBuffer(0, 0x5A) && full.readDataBuffer(0, b) && b == 0x5A, "data_buffer_roundtrip");
    check_true(full.enableInterrupt(AXP2101Full::SOURCE_VBUS_INSERT), "enable_interrupt");
    full.pollInterrupt();
    check_true(full.valid(), "poll_interrupt");

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() { delay(1000); }
