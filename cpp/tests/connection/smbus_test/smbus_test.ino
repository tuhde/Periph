#include <Wire.h>
#include "SMBusConnection.h"

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 400000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

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

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);

    // --- address validation ---
    {
        SMBusConnection bad(Wire, 0x07);
        check_true("addr 0x07 rejected", !bad.valid());
    }
    {
        SMBusConnection bad(Wire, 0x78);
        check_true("addr 0x78 rejected", !bad.valid());
    }

    // --- basic I/O without PEC ---
    SMBusConnection connection(Wire, TEST_ADDR);

    uint8_t buf[1] = {0};
    connection.read(buf, 1);
    check_true("read accepted", connection.valid());

    uint8_t reg[1] = {0x00};
    connection.write(reg, 1);
    check_true("write accepted", connection.valid());

    connection.write_read(reg, 1, buf, 1);
    check_true("write_read accepted", connection.valid());

    // --- write with PEC enabled ---
    SMBusConnection transport_pec(Wire, TEST_ADDR, true);
    transport_pec.write(reg, 1);
    check_true("write with PEC accepted", transport_pec.valid());

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
