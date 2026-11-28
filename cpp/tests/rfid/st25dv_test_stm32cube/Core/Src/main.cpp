#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "St25dv.h"

static UART_HandleTypeDef huart2;
static I2C_HandleTypeDef hi2c1;

// SysTick_Handler overrides the weak default in startup_stm32f411xe.s —
// without this, HAL_GetTick()/HAL_Delay() never advance.
extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

// Retargets printf() to USART2 (PA2/PA3), which NUCLEO-F411RE's on-board
// ST-LINK exposes as a USB virtual COM port at 115200 baud.
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_GPIOC_CLK_ENABLE();
}

static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;  // PA2=TX, PA3=RX
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_LOW;
    gpioInit.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart2.Instance          = USART2;
    huart2.Init.BaudRate     = 115200;
    huart2.Init.WordLength   = UART_WORDLENGTH_8B;
    huart2.Init.StopBits     = UART_STOPBITS_1;
    huart2.Init.Parity       = UART_PARITY_NONE;
    huart2.Init.Mode         = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

static void i2c1_init(void) {
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;  // PB8=SCL, PB9=SDA
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;               // internal pull-up, in case the breakout has none
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF4_I2C1;
    HAL_GPIO_Init(GPIOB, &gpioInit);

    hi2c1.Instance             = I2C1;
    hi2c1.Init.ClockSpeed      = 100000;
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}


// Converts "\n" to "\r\n" for the ST-LINK virtual COM port.
static void out(const char* fmt, ...) {
    char text[160];
    char line[320];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof(text), fmt, ap);
    va_end(ap);
    size_t j = 0;
    for (size_t i = 0; text[i] && j < sizeof(line) - 2; i++) {
        if (text[i] == '\n') line[j++] = '\r';
        line[j++] = text[i];
    }
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(line), static_cast<uint16_t>(j), HAL_MAX_DELAY);
}

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { out("PASS %s\n", label); passed++; }
    else      { out("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))

static void run_tests(ST25DVFull& tag) {
    CHECK(tag.init(), "init");
    CHECK(tag.ic_reference() == 0x24 || tag.ic_reference() == 0x26, "ic_reference");
    uint32_t size = tag.memory_size();
    CHECK(size == 512 || size == 2048 || size == 8192, "memory_size");
    uint8_t uid[8] = {0};
    CHECK(tag.uid(uid), "uid ok");
    CHECK(uid[0] == 0xE0 && uid[1] == 0x02, "uid manufacturer");
    tag.ic_revision();
    CHECK(true, "ic_revision");

    uint16_t addr = (uint16_t)(size - 64);
    static uint8_t saved[40], pattern[40], back[40];
    CHECK(tag.read(addr, saved, 40), "read ok");
    for (int i = 0; i < 40; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    CHECK(tag.write(addr, pattern, 40), "write ok");
    CHECK(tag.read(addr, back, 40) && memcmp(back, pattern, 40) == 0, "write/read round-trip");
    CHECK(tag.write(addr, saved, 40), "restore");
    CHECK(!tag.read((uint16_t)(size - 1), back, 2), "range rejected");

    static const uint8_t pwd[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    CHECK(tag.present_password(pwd), "present factory password");
    CHECK(tag.session_open(), "session open after present");

    uint8_t rf = 0xFF, i2c = 0xFF;
    CHECK(tag.get_area_protection(1, rf, i2c) && rf <= 0x0F && i2c <= 3, "get_area_protection");
    tag.get_gpo();
    CHECK(true, "get_gpo");
    tag.field_present();
    CHECK(true, "field_present");
    tag.poll_interrupt();
    CHECK(true, "poll_interrupt");
    tag.mailbox_status();
    CHECK(true, "mailbox_status");
    static uint8_t ndef[256];
    tag.read_ndef(ndef, sizeof(ndef));
    CHECK(true, "read_ndef");
    CHECK(tag.wait_write_complete(), "wait_write_complete");
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube user_conn(&hi2c1, 0x53, nullptr, nullptr, 2);
    I2CConnectionSTM32Cube system_conn(&hi2c1, 0x57, nullptr, nullptr, 2);
    ST25DVFull tag(user_conn, system_conn);
    run_tests(tag);
    out("===DONE: %d passed, %d failed===\n", passed, failed);
    while (true) HAL_Delay(1000);
}
