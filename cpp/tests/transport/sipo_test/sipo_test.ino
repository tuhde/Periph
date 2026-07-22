#include "SiPoTransport.h"

#ifndef TEST_SER_IN_PIN
#define TEST_SER_IN_PIN 8
#endif
#ifndef TEST_SRCK_PIN
#define TEST_SRCK_PIN   9
#endif
#ifndef TEST_RCK_PIN
#define TEST_RCK_PIN    10
#endif
#ifndef TEST_SRCLR_PIN
#define TEST_SRCLR_PIN  11
#endif
#ifndef TEST_G_PIN
#define TEST_G_PIN      12
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

    SiPoTransport transport(TEST_SER_IN_PIN, TEST_SRCK_PIN, TEST_RCK_PIN,
                            TEST_SRCLR_PIN, TEST_G_PIN);

    uint8_t data1[] = { 0xA5 };
    transport.write(data1, sizeof(data1));
    check_true("write accepted", true);

    uint8_t data2[] = { 0x00, 0xFF };
    transport.write(data2, sizeof(data2));
    check_true("write multi-byte accepted", true);

    check_true("clear returns true when configured", transport.clear());

    check_true("set_output_enable(false) returns true when configured",
               transport.set_output_enable(false));
    check_true("set_output_enable(true) returns true when configured",
               transport.set_output_enable(true));

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
