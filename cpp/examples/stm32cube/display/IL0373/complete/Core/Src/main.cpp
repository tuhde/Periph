// IL0373 complete example: Waveshare 2.13inch e-Paper (D) on a NUCLEO-F411RE.
//
// Exercises the whole driver API in stages and prints each step with its duration
// to USART2. Every full refresh blocks until BUSY goes high; the panel datasheet gives
// no update time, so each stage prints the measured duration.
//
//   1  frame on white                    clear, set_pixel, show
//   2  drawing helpers                   draw_rect, draw_line, blit, get_pixel
//   3  partial window update             show_region
//   4  settings (power-on values)        set_flip, set_power, set_frame_rate,
//                                        set_soft_start, set_interval, set_temperature_sensor
//   5  one CS# window per byte           set_data_chunk
//   6  read commands                     read_status, read_temperature (need DOUT wired)
//   7  deep sleep, wake                  sleep, wake
//
// Not exercised: set_vcom_dc (a wrong value costs contrast) and write_lut (needs the
// vendor's waveform tables).
//
// Wiring (Waveshare e-Paper Shield / Arduino header, 3.3 V logic):
//   DIN  = PA7 (D11, SPI1 MOSI)     CLK  = PA5 (D13, SPI1 SCK)
//   CS   = PB6 (D10)                DC   = PC7 (D9)
//   RST  = PA9 (D8)                 BUSY = PA8 (D7)
//   VCC  = 3V3                      GND  = GND
// Output goes to USART2 (ST-LINK virtual COM port, 115200 8N1).
#include <stdio.h>
#include <stdint.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DisplayConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "IL0373.h"

typedef IL0373Full<DisplayConnectionSTM32Cube> Epd;

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

// 104 x 212 pixels, one bit each: 13 bytes per row, 212 rows.
static uint8_t frameBuffer[2756];

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

// BUSY is a push-pull output of the controller: a plain floating input.
// InputPinSTM32Cube reads the pin as configured, so set it up here.
static void busy_pin_init(void) {
    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin  = GPIO_PIN_8;                     // PA8 = BUSY (D7)
    gpioInit.Mode = GPIO_MODE_INPUT;
    gpioInit.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOA, &gpioInit);
}

static void log_show(const char* what, bool ok, uint32_t t0) {
    printf("  %s: %s after %lu ms\r\n", what, ok ? "done" : "FAILED (BUSY timeout)",
           (unsigned long)(HAL_GetTick() - t0));
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
    InputPinSTM32Cube  busy(GPIOA, GPIO_PIN_8);     // BUSY pin, (port, pin)

    HAL_Delay(1000);
    printf("\r\nIL0373 complete example, NUCLEO-F411RE, SPI1 0.78 MHz\r\n");

    DisplayConnectionSTM32Cube connection(&hspi1, &cs, &dc, &res, &busy);  // Create display connection, (hspi, cs, dc, res, busy)
                                                                           // wraps SPI1 with CS#, D/C#, RES# and BUSY
    Epd epd(connection, frameBuffer);       // Create IL0373 driver, (connection, buffer, width=104, height=212, busy_timeout_ms=30000)
                                                    // resets the controller, powers the booster and writes PSR, TRES and CDI
    if (!epd.valid()) {
        printf("IL0373 init failed: BUSY never went high\r\n");
        while (true) HAL_Delay(1000);
    }
    printf("driver initialised, %ux%u\r\n", (unsigned)epd.width(), (unsigned)epd.height());

    // ---- stage 1: Minimal API ---------------------------------------------------------------
    printf("[1] frame on white\r\n");
    epd.clear(Epd::WHITE);                          // Fill framebuffer, (color=WHITE)
                                                    // sets every pixel white in the framebuffer
    for (int16_t x = 0; x < epd.width(); x++) {
        epd.set_pixel(x, 0, Epd::BLACK);            // Set pixel, (x, y, color=BLACK)
                                                    // black pixel on the top edge
        epd.set_pixel(x, epd.height() - 1, Epd::BLACK);  // Set pixel, (x, y, color=BLACK)
                                                    // black pixel on the bottom edge
    }
    for (int16_t y = 0; y < epd.height(); y++) {
        epd.set_pixel(0, y, Epd::BLACK);            // Set pixel, (x, y, color=BLACK)
                                                    // black pixel on the left edge
        epd.set_pixel(epd.width() - 1, y, Epd::BLACK);   // Set pixel, (x, y, color=BLACK)
                                                    // black pixel on the right edge
    }
    {
        uint32_t t0 = HAL_GetTick();
        bool ok = epd.show();                       // Update display, () → bool
                                                    // writes OLD and NEW and runs DRF, CDI, POF
        log_show("show", ok, t0);
    }
    HAL_Delay(3000);

    // ---- stage 2: drawing helpers ---------------------------------------------------------------
    printf("[2] drawing helpers\r\n");
    epd.clear();                                    // Fill framebuffer, (color=WHITE)
                                                    // starts from a white picture
    epd.draw_rect(6, 6, 92, 200, Epd::BLACK);       // Draw rectangle outline, (x, y, w, h, color=BLACK, fill=false)
                                                    // black border inset by 6 pixels
    epd.draw_rect(16, 16, 40, 30, Epd::BLACK, true);  // Draw filled rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                    // solid black block in the upper left
    epd.draw_line(6, 6, 97, 205, Epd::BLACK);       // Draw line, (x0, y0, x1, y1, color=BLACK)
                                                    // black diagonal across the picture
    epd.draw_line(6, 205, 97, 6, Epd::BLACK);       // Draw line, (x0, y0, x1, y1, color=BLACK)
                                                    // second diagonal crossing the first
    {
        // 8 x 8 checkerboard bitmap, one bit per pixel, MSB first.
        static const uint8_t checker[8] = {0xAA, 0x55, 0xAA, 0x55, 0xAA, 0x55, 0xAA, 0x55};
        epd.blit(80, 20, checker, 8, 8, Epd::BLACK);   // Copy bitmap, (x, y, data, w, h, color=BLACK)
                                                    // draws only the set bits, in black
    }
    printf("  get_pixel(20,20)=%u (black=%u), get_pixel(80,20)=%u (black=%u)\r\n",
           (unsigned)epd.get_pixel(20, 20), (unsigned)Epd::BLACK,        // Read pixel, (x, y) → color
                                                                       // returns BLACK or WHITE
           (unsigned)epd.get_pixel(80, 20), (unsigned)Epd::BLACK);
    {
        uint32_t t0 = HAL_GetTick();
        bool ok = epd.show();                       // Update display, () → bool
                                                    // full update with the drawn picture
        log_show("show", ok, t0);
    }
    HAL_Delay(3000);

    // ---- stage 3: partial window ---------------------------------------------------------------------
    printf("[3] partial window\r\n");
    epd.draw_rect(40, 120, 40, 40, Epd::BLACK, true);  // Draw filled rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                    // black block in the middle
    {
        uint32_t t0 = HAL_GetTick();
        bool ok = epd.show_region(40, 120, 40, 40);  // Update window, (x, y, w, h) → bool
                                                    // sends only that window's rows and refreshes it
        log_show("show_region", ok, t0);
    }
    HAL_Delay(3000);

    // ---- stage 4: settings written with their power-on values ----------------------------------------
    printf("[4] settings (power-on values, no visible change)\r\n");
    epd.set_flip(false, false);                     // Mirror picture, (horizontal, vertical)
                                                    // no mirroring, the power-on scan direction
    epd.set_power(14.0f, 14.0f, 20.0f);             // Set source/gate power, (vdh, vdl, vgh=20)
                                                    // the power-on voltages: 14 V, 14 V, 20 V
    epd.set_frame_rate(50);                         // Set frame rate, (hz)
                                                    // 50 Hz, the power-on rate (M=7, N=4)
    epd.set_soft_start(0x17, 0x17, 0x17);           // Set booster soft start, (a, b, c)
                                                    // the power-on soft-start phases
    epd.set_interval(7, 2, 1);                      // Set VCOM and data interval, (cdi, border=2, polarity=1)
                                                    // the power-on interval; polarity 1 keeps white = 1
    epd.set_temperature_sensor(false, 0);           // Select temperature sensor, (external, offset=0)
                                                    // internal sensor, no offset
    // set_vcom_dc() and write_lut() are not exercised: a wrong VCOM_DC costs contrast and
    // the waveform tables need the vendor's data.
    printf("  settings written\r\n");

    // ---- stage 5: one CS# window per byte through the driver ---------------------------------------------
    printf("[5] one CS# window per byte\r\n");
    epd.set_data_chunk(1);                          // Set pixel transfer size, (bytes)
                                                    // 1 = one CS# window per byte, like the vendor sequence
    {
        uint32_t t0 = HAL_GetTick();
        bool ok = epd.show();                       // Update display, () → bool
                                                    // same picture, byte-wise CS# framing
        log_show("show", ok, t0);
    }
    epd.set_data_chunk(0);                          // Set pixel transfer size, (bytes)
                                                    // back to one transfer per plane
    HAL_Delay(3000);

    // ---- stage 6: read commands (need DOUT wired) ----------------------------------------------------------
    printf("[6] read commands\r\n");
    {
        uint8_t status = 0;
        if (epd.read_status(&status)) {             // Read status, (status) → bool
                                                    // FLG: PON, POF, BUSY and data flags
            printf("  FLG = 0x%02X\r\n", status);
        } else {
            printf("  reads unavailable: the connection is simplex (DIN only)\r\n");
        }
        int8_t temperature = 0;
        if (epd.read_temperature(&temperature)) {   // Read temperature, (celsius) → bool
                                                    // internal sensor, signed degrees Celsius
            printf("  temperature = %d C\r\n", (int)temperature);
        }
    }

    // ---- stage 7: deep sleep, wake ------------------------------------------------------------------------
    printf("[7] sleep, wake\r\n");
    epd.sleep();                                    // Enter deep sleep, () → bool
                                                    // border floating, POF and DSLP with check code 0xA5
    HAL_Delay(1000);
    epd.wake();                                     // Wake from deep sleep, () → bool
                                                    // hardware reset and initialisation
    epd.sleep();                                    // Enter deep sleep, () → bool
                                                    // leave the panel powered down

    printf("all stages finished\r\n");
    while (true) {
        HAL_Delay(1000);
    }
}
