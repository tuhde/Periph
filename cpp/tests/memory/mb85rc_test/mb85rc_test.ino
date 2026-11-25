#include <Wire.h>
#include <Periph.h>
#include <string.h>

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
#define TEST_ADDR 0x50
#endif

I2CConnection connection(Wire, TEST_ADDR, nullptr, nullptr, 2);
I2CConnection idConnection(Wire, 0x7C);
MB85RCFull fram(connection);

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

#if defined(ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);
#else
    Wire.begin();
    Wire.setClock(TEST_I2C_FREQ);
#endif

    check_true("size", fram.size() == 32768);

    fram.write_byte(0x0010, 0x5A);
    check_true("write_byte/read_byte round-trip", fram.read_byte(0x0010) == 0x5A);

    uint8_t data[100];
    uint8_t back[100];
    for (uint8_t i = 0; i < 100; i++) data[i] = i;
    fram.write(0x0100, data, 100);
    fram.read(0x0100, back, 100);
    check_true("chunked write/read round-trip", memcmp(data, back, 100) == 0);

    fram.fill(0x0100, 100, 0xC3);
    fram.read(0x0100, back, 100);
    bool filled = true;
    for (uint8_t i = 0; i < 100; i++) filled = filled && back[i] == 0xC3;
    check_true("fill", filled);
    fram.fill(0x0100, 100);
    fram.read(0x0100, back, 100);
    bool zeroed = true;
    for (uint8_t i = 0; i < 100; i++) zeroed = zeroed && back[i] == 0;
    check_true("fill default zero", zeroed);

    const uint8_t tail[2] = {0x12, 0x34};
    uint8_t tail_back[2] = {0, 0};
    fram.write(0x7FFE, tail, 2);
    fram.read(0x7FFE, tail_back, 2);
    check_true("last bytes round-trip", tail_back[0] == 0x12 && tail_back[1] == 0x34);

    check_true("wrap rejected", !fram.read(0x7FFF, tail_back, 2));

    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    fram.read_device_id(idConnection, &manufacturer, &density, &product, TEST_ADDR);
    if (manufacturer == 0 && density == 0 && product == 0)
        Serial.println("SKIP read_device_id (0x7C not answering)");
    else
        check_true("read_device_id", manufacturer == 0x00A && density == 0x5 && product == 0x10);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
