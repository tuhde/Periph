// UC8276C demo: Waveshare 4.2inch e-Paper (B) on a NUCLEO-F411RE.
//
// Follows the captured vendor sequence (reset, power-on, panel setting, both
// pixel planes, display refresh), through the Periph driver: a 4-pixel black frame
// and a red block on a white screen, then deep sleep. show() blocks for the whole
// refresh, about 17 s.
//
// Wiring (Waveshare e-Paper Shield / Arduino header, 3.3 V logic):
//   DIN  = PA7 (D11, SPI1 MOSI)     CLK  = PA5 (D13, SPI1 SCK)
//   CS   = PB6 (D10)                DC   = PC7 (D9)
//   RST  = PA9 (D8)                 BUSY = PA8 (D7)
//   VCC  = 3V3                      GND  = GND
// BUSY is BUSY_N on this controller: low while busy. Progress goes to USART2
// (ST-LINK virtual COM port, 115200 8N1).
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DisplayConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "UC8276C.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

// 400 x 300 pixels, one bit each: 50 bytes per row, 300 rows.
static uint8_t bwBuffer[15000];
static uint8_t redBuffer[15000];

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
// MSB first. SPI1 is on APB2 (100 MHz): /128 gives 0.78 MHz, close to the ~1 MHz
// of the captured vendor sequence (the controller allows up to 10 MHz writes).
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
    hspi1.Init.BaudRatePrescaler = SPI_BAUDRATEPRESCALER_128;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

// BUSY_N is a push-pull output of the controller: a plain floating input.
// InputPinSTM32Cube reads the pin as configured, so set it up here.
static void busy_pin_init(void) {
    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin  = GPIO_PIN_8;                     // PA8 = BUSY (D7)
    gpioInit.Mode = GPIO_MODE_INPUT;
    gpioInit.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOA, &gpioInit);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init();
    busy_pin_init();

    OutputPinSTM32Cube cs(GPIOB, GPIO_PIN_6);       // CS# pin, (port, pin)
    OutputPinSTM32Cube dc(GPIOC, GPIO_PIN_7);       // D/C# pin, (port, pin)
    OutputPinSTM32Cube res(GPIOA, GPIO_PIN_9);      // RES# pin, (port, pin)
    InputPinSTM32Cube  busy(GPIOA, GPIO_PIN_8);     // BUSY_N pin, (port, pin)

    typedef UC8276CFull<DisplayConnectionSTM32Cube> Epd;

    DisplayConnectionSTM32Cube connection(&hspi1, &cs, &dc, &res, &busy);  // Create display connection, (hspi, cs, dc, res, busy)
    Epd epd(connection, bwBuffer, redBuffer);       // Create UC8276C driver, (connection, bw, red, width=400, height=300, busy_timeout_ms=30000)

    if (!epd.valid()) {
        printf("UC8276C init failed: BUSY_N never went high (check wiring and 3V3)\r\n");
        while (true) HAL_Delay(1000);
    }

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 4 px (a frame on the very edge is
    // hidden by the panel bezel) and a red header bar across the top 40 rows.
    epd.clear(Epd::WHITE);                          // Fill framebuffer, (color=WHITE)
    epd.draw_rect(4, 4, 392, 292, Epd::BLACK);      // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.draw_rect(5, 5, 390, 40, Epd::RED, true);   // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is red to flag the peak.
    static const int16_t values[8] = {30, 55, 80, 45, 120, 90, 60, 100};
    int16_t peak = 0;
    for (int i = 0; i < 8; i++) {
        if (values[i] > peak) peak = values[i];
    }
    epd.draw_line(30, 270, 370, 270, Epd::BLACK);   // Draw line, (x0, y0, x1, y1, color=BLACK)
    epd.draw_line(30, 70, 30, 270, Epd::BLACK);     // Draw line, (x0, y0, x1, y1, color=BLACK)
    for (int i = 0; i < 8; i++) {
        epd.draw_rect(45 + i * 40, 270 - values[i], 28, values[i],
                      values[i] == peak ? Epd::RED : Epd::BLACK, true);  // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/PON/DRF/POF sequence (about 17 s).
    uint32_t t0 = HAL_GetTick();
    bool ok = epd.show();                           // Update display, () → bool
    printf(ok ? "refresh took %lu ms\r\n" : "BUSY_N timeout after %lu ms\r\n",
           (unsigned long)(HAL_GetTick() - t0));

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep();                                    // Enter deep sleep, () → bool

    while (true) {
        HAL_Delay(1000);
    }
}
