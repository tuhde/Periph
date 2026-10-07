#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "UARTConnectionSTM32Cube.h"
#include "NEO6.h"

static UART_HandleTypeDef huart2;
static UART_HandleTypeDef huart1;

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

// USART1 on PA9=TX (D8) / PA10=RX (D2). USART2 is taken by the ST-LINK virtual COM port.
static void uart1_init(uint32_t baud) {
    __HAL_RCC_USART1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_9 | GPIO_PIN_10;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_PULLUP;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF7_USART1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart1.Instance          = USART1;
    huart1.Init.BaudRate     = baud;
    huart1.Init.WordLength   = UART_WORDLENGTH_8B;
    huart1.Init.StopBits     = UART_STOPBITS_1;
    huart1.Init.Parity       = UART_PARITY_NONE;
    huart1.Init.Mode         = UART_MODE_TX_RX;
    huart1.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart1.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    uart1_init(9600);
    UARTConnectionSTM32Cube connection(&huart1);
    NEO6Full gps(connection);

    unsigned long startMs;

    startMs = HAL_GetTick();
    while (true) {

    if (HAL_GetTick() - startMs >= 60000) {
        while (true) HAL_Delay(1000);  // done
    }

    bool gotFix = gps.update();                          // Read + parse one NMEA sentence, () → bool

    // --- No fix yet: show the wait state ---
    // gpsFix alone would not be trustworthy here; update() already only
    // reports true once the GGA fix-status field confirms a real fix, so
    // a plain fix() == 0 check is enough to detect the waiting state.
    if (gps.fix() == 0) {
        printf("waiting for fix... satellites in use: ");
        printf("%d\r\n", gps.satellites());
    }
    // --- Fix acquired: log the full position record ---
    // Cold-start TTFF is ~26 s typical outdoors; once gotFix flips true the
    // position, altitude, and HDOP fields below are all populated together.
    else if (gotFix) {
        printf("%d", gps.utcTime());
        printf("  lat=");
        printf("%.6f", gps.latitude());
        printf("  lon=");
        printf("%.6f", gps.longitude());
        printf("  alt=");
        printf("%.1f", gps.altitude());
        printf(" m  sats=");
        printf("%d", gps.satellites());
        printf("  hdop=");
        printf("%d\r\n", gps.hdop());
    }

    HAL_Delay(200);
        HAL_Delay(10);
    }

}
