#include <Arduino.h>
#include "UARTTransport.h"

// Assumes a loopback jumper bridging TX and RX pins on the UART port under test.
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

    const uint8_t payload[1] = {0x42};
    transport.write(payload, 1);
    check_true("write accepted", true);

    uint8_t buf[1] = {0};
    transport.read(buf, 1);
    check_true("read returns 1 byte", true);
    check_true("loopback byte matches", buf[0] == 0x42);

    const uint8_t cmd[2] = {0xA5, 0x5A};
    uint8_t resp[2] = {0, 0};
    transport.write_read(cmd, 2, resp, 2);
    check_true("write_read returns data", resp[0] == 0xA5 && resp[1] == 0x5A);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
