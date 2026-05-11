#include <SPI.h>
#include "SPITransport.h"

#ifndef TEST_SPI_MOSI
#define TEST_SPI_MOSI 11
#endif
#ifndef TEST_SPI_MISO
#define TEST_SPI_MISO 12
#endif
#ifndef TEST_SPI_SCK
#define TEST_SPI_SCK  13
#endif
#ifndef TEST_SPI_CS
#define TEST_SPI_CS   10
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { Serial.print("PASS "); Serial.println(label); passed++; }
    else           { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    SPI.begin(TEST_SPI_SCK, TEST_SPI_MISO, TEST_SPI_MOSI);

    SPISettings settings(1_000_000, MSBFIRST, SPI_MODE0);
    SPITransport transport(SPI, TEST_SPI_CS, settings);

    uint8_t tx_data[] = {0x01, 0x02, 0x03};
    uint8_t rx_buf[3] = {0};

    transport.write(tx_data, sizeof(tx_data));
    check_true("write completed", true);

    transport.read(rx_buf, sizeof(rx_buf));
    check_true("read completed", true);

    uint8_t cmd[] = {0x55, 0xAA};
    uint8_t resp[2] = {0};
    transport.write_read(cmd, sizeof(cmd), resp, sizeof(resp));
    check_true("write_read completed", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}