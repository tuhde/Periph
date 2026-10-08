#include <stdio.h>
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    HAL_Delay(2000);

    DHTxxConnectionSTM32Cube connection(GPIOA, GPIO_PIN_8);
    DHT11Full dht(connection, 3);                                   // Create DHT11 driver, (connection, max_retries=3)

    while (1) {
        float t = dht.read_temperature();                          // Read temperature, () → float °C
                                                                   // returns a fresh conversion each call
        float h = dht.read_humidity();                             // Read humidity, () → float %RH
                                                                   // returns a fresh conversion each call
        float t2, h2;
        bool ok = dht.read_retry(5, t2, h2);                      // Read with retries, (max_retries 1..255, t out, h out) → bool ok
                                                                   // retries up to 5 times on checksum error
        uint8_t raw[5];
        bool rok = dht.read_raw_with_retry(raw);                   // Read raw frame, (out[5]) → bool ok
                                                                   // returns the validated 5-byte frame
        printf("t=%.1f h=%.1f retry_ok=%d raw[0]=0x%02X\r\n",
               t, h, ok, raw[0]);
        HAL_Delay(2000);
    }
}
