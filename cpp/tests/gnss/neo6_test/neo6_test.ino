#include <Arduino.h>
#include "UARTTransport.h"
#include "NEO6.h"

// Requires a NEO-6 module wired to UART with a clear sky view. Achieving an
// actual fix needs an outdoor antenna and can take up to ~26 s (cold start);
// this test only requires that well-typed values come back, not a fix.
#ifndef TEST_UART_BAUDRATE
#define TEST_UART_BAUDRATE 9600
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

    Serial1.begin(TEST_UART_BAUDRATE);
    Serial1.setTimeout(1000);
    UARTTransport transport(Serial1);
    NEO6Minimal gps(transport);

    check_true("fix() starts at 0", gps.fix() == 0);
    check_true("latitude() starts at NAN", isnan(gps.latitude()));

    for (int i = 0; i < 3000; i++) {
        gps.update();
    }

    check_true("fix() is a valid quality code", gps.fix() == 0 || gps.fix() == 1 || gps.fix() == 2);
    check_true("satellites() is a non-negative int", gps.satellites() >= 0);
    if (gps.fix() > 0) {
        check_true("latitude() in range once fixed", gps.latitude() >= -90.0f && gps.latitude() <= 90.0f);
        check_true("longitude() in range once fixed", gps.longitude() >= -180.0f && gps.longitude() <= 180.0f);
        check_true("altitude() is populated once fixed", !isnan(gps.altitude()));
    } else {
        Serial.println("note: no fix acquired during the test window (needs sky view)");
    }

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
