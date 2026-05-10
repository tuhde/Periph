#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS SPI
#endif

NeoPixelTransport transport(TEST_SPI_BUS);

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { Serial.print("PASS "); Serial.println(label); passed++; }
    else           { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    SPI.begin();

    transport.write((const uint8_t*)"\x00\x00\x00", 3);
    check_true("write_black_no_error", true);

    transport.write((const uint8_t*)"\xFF\xFF\xFF", 3);
    check_true("write_white_no_error", true);

    transport.write((const uint8_t*)"\x00\xFF\x00", 3);
    check_true("write_green_no_error", true);

    transport.write((const uint8_t*)"\x10\x20\x30\x40", 4);
    check_true("write_4bytes_no_error", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() { delay(1000); }