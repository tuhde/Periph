#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DHTxxConnectionSTM32Cube.h"
#include "DHT11.h"

static UART_HandleTypeDef huart2;

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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

class MockConnection : public DHTxxConnectionSTM32Cube {
public:
    MockConnection(const uint8_t* frame) : DHTxxConnectionSTM32Cube(GPIOA, GPIO_PIN_8), _frame(frame) {}
    bool read(uint8_t* out) {
        for (uint8_t i = 0; i < 5; i++) out[i] = _frame[i];
        return true;
    }
private:
    const uint8_t* _frame;
};

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    // Test 1: Decode datasheet example
    const uint8_t frame1[5] = { 0x35, 0x00, 0x18, 0x04, 0x51 };
    MockConnection t1(frame1);
    DHT11Minimal dht_min(t1);
    float temperature, humidity;
    bool ok = dht_min.read(temperature, humidity);
    check_true(ok && fabs(temperature - 24.4f) < 0.001f && fabs(humidity - 53.0f) < 0.001f, "decode_datasheet_example");

    // Test 2: Negative temperature
    const uint8_t frame2[5] = { 0x20, 0x00, 0x0A, 0x81, 0xAB };
    MockConnection t2(frame2);
    DHT11Minimal dht_min2(t2);
    ok = dht_min2.read(temperature, humidity);
    check_true(ok && fabs(temperature - (-10.1f)) < 0.001f && fabs(humidity - 32.0f) < 0.001f, "decode_negative_temperature");

    // Test 3: Checksum error
    const uint8_t bad_frame[5] = { 0x35, 0x00, 0x18, 0x04, 0x00 };
    MockConnection t3(bad_frame);
    DHT11Minimal dht_min3(t3);
    ok = dht_min3.read(temperature, humidity);
    check_true(!ok && !dht_min3.valid(), "checksum_error_invalidates");

    // Test 4: DHT11Full
    MockConnection t4(frame1);
    DHT11Full dht_full(t4, 3);
    check_true(fabs(dht_full.read_temperature() - 24.4f) < 0.001f, "read_temperature");
    check_true(fabs(dht_full.read_humidity() - 53.0f) < 0.001f, "read_humidity");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
