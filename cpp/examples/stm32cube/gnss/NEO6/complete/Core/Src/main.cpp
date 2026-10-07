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

    gps.setRate(1);                                      // Set navigation update rate, (hz) → void
                                                          // writes CFG-RATE with measRate = 1000/hz ms
    gps.setPlatform(0);                                   // Set dynamic platform model, (model 0-8) → void
                                                          // writes CFG-NAV5 with mask=dynModel only
    gps.saveConfig();                                     // Persist current configuration, () → void
                                                          // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM
    while (true) {

    if (gps.update()) {                                  // Read + parse one NMEA sentence, () → bool
        printf("%.6f", gps.latitude());
        printf(", ");
        printf("%.6f", gps.longitude());
        printf(", ");
        printf("%.1f\r\n", gps.altitude());
                                                          // decimal degrees, decimal degrees, meters MSL
        printf("%d", gps.speed());                       // Speed over ground, () → m/s
        printf(", ");
        printf("%d\r\n", gps.course());                    // Course over ground, () → deg
        printf("%d", gps.utcTime());                      // UTC time of last fix sentence, () → hhmmss.ss
        printf(", ");
        printf("%d\r\n", gps.utcDate());                    // UTC date of last RMC sentence, () → ddmmyy
        printf("%d\r\n", gps.hdop());                       // Horizontal dilution of precision, () → float

        uint8_t payload[256];
        size_t payloadLen = 0;
        if (gps.pollUbx(0x01, 0x03, payload, payloadLen, sizeof(payload))) {  // Poll a UBX message, (msg_class, msg_id, out_payload, out_len, max_len) → bool
            printf("NAV-STATUS payload bytes: ");
            printf("%d\r\n", payloadLen);
        }

        gps.coldStart();                                  // Force a cold start via CFG-RST, () → void
    }
    HAL_Delay(50);
        HAL_Delay(10);
    }

}
