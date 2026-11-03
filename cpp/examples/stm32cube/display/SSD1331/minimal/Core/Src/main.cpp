// SSD1331 minimal example: Waveshare 0.95inch RGB OLED on a NUCLEO-F411RE.
//
// Minimal use: eight colour bars and a white frame, drawn into the framebuffer and sent once.
//
// Wiring (same pins as the Waveshare e-Paper Shield, no BUSY line, 3.3 V logic):
//   DIN  = PA7 (D11, SPI1 MOSI)     CLK  = PA5 (D13, SPI1 SCK)
//   CS   = PB6 (D10)                DC   = PC7 (D9)
//   RST  = PA9 (D8)                 VCC  = 3V3      GND = GND
// Progress goes to USART2 (ST-LINK virtual COM port, 115200 8N1).
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DisplayConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "SSD1331.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

// 96 x 64 pixels, RGB565 (2 bytes each, high byte first).
static uint8_t frameBuffer[96 * 64 * 2];

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

// SPI1 write-only on the Arduino header: PA5=SCK (D13), PA7=MOSI (D11). Mode 0,
// MSB first. SPI1 is on APB2 (100 MHz): /32 gives 3.1 MHz; the SSD1331 allows
// up to 6.6 MHz writes (150 ns cycle), the vendor demo ran at about 3 MHz.
static void spi1_init(void) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    hspi1.Instance               = SPI1;
    hspi1.Init.Mode              = SPI_MODE_MASTER;
    hspi1.Init.Direction         = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize          = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity       = SPI_POLARITY_LOW;
    hspi1.Init.CLKPhase          = SPI_PHASE_1EDGE;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = SPI_BAUDRATEPRESCALER_32;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init();

    OutputPinSTM32Cube cs(GPIOB, GPIO_PIN_6);       // CS# pin, (port, pin)
    OutputPinSTM32Cube dc(GPIOC, GPIO_PIN_7);       // D/C# pin, (port, pin)
    OutputPinSTM32Cube res(GPIOA, GPIO_PIN_9);      // RES# pin, (port, pin)

    DisplayConnectionSTM32Cube connection(&hspi1, &cs, &dc, &res, nullptr);  // Create display connection, (hspi, cs, dc, res, busy=none)
    SSD1331Minimal<DisplayConnectionSTM32Cube> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)

    if (!oled.valid()) {
        printf("SSD1331 init failed (check wiring)\r\n");
        while (true) HAL_Delay(1000);
    }

    static const uint16_t bars[8] = {
        oled.WHITE, oled.YELLOW, oled.CYAN, oled.GREEN, oled.MAGENTA, oled.RED, oled.BLUE, oled.BLACK
    };
    for (int16_t y = 0; y < oled.height(); y++) {
        for (int16_t x = 0; x < oled.width(); x++) {
            oled.set_pixel(x, y, bars[x / 12]);     // Set pixel, (x, y, color=WHITE)
        }
    }
    for (int16_t x = 0; x < oled.width(); x++) {
        oled.set_pixel(x, 0, oled.WHITE);           // Set pixel, (x, y, color=WHITE)
        oled.set_pixel(x, oled.height() - 1, oled.WHITE);
    }

    oled.show();                                    // Send framebuffer, () → None
    printf("done\r\n");

    while (true) {
        HAL_Delay(1000);
    }
}
