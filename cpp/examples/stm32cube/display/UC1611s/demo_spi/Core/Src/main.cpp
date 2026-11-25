// UC1611s demo example (4-wire SPI): EA DOGXL240-7, STM32Cube HAL (NUCLEO-F411RE, SPI1)
// Wiring: SCK=PA5 (D13), SI=PA7 (D11), CS0=PB6, CD=PC7, RST=PA9; VDD = 3V3
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DisplayConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "UC1611s.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

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

// SPI1 write-only on the Arduino header: PA5=SCK (D13), PA7=MOSI (D11). Mode 0, MSB first.
// SPI1 is on APB2 (100 MHz): /16 gives 6.25 MHz; the module allows up to 8 MHz.
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
    hspi1.Init.BaudRatePrescaler = SPI_BAUDRATEPRESCALER_16;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

#define PRINT(...) printf(__VA_ARGS__)
#define DELAY_MS(ms) HAL_Delay(ms)

template <typename LCD>
static void run(LCD& lcd) {
    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear();                                    // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 240, 128);                  // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()
    
    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second, instead of the 8 of a full-frame show().
    int16_t bx = 20, by = 12, dx = 3, dy = 2;
    for (int frame = 0; frame < 200; frame++) {
        int16_t oldy = by;
        lcd.draw_rect(bx, by, 8, 8, false, true);   // Draw rectangle, (x, y, w, h, on=true, fill=false)
        bx += dx;
        by += dy;
        if (bx < 2 || bx > 229) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 118) { dy = -dy; by += 2 * dy; }
        lcd.draw_rect(bx, by, 8, 8, true, true);    // Draw rectangle, (x, y, w, h, on=true, fill=false)
        int16_t first = (oldy < by ? oldy : by) / 8;
        int16_t last = ((oldy > by ? oldy : by) + 7) / 8;
        for (int16_t page = first; page <= last; page++) {
            lcd.show_page(static_cast<uint8_t>(page));  // Send one framebuffer page, (page 0..15)
        }
        DELAY_MS(20);
    }
    
    // --- Contrast sweep around the working point ---
    // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
    for (int pm = 0x8F; pm >= 0x70; pm -= 2) {
        lcd.set_contrast(static_cast<uint8_t>(pm));  // Set contrast, (value 0..255)
        DELAY_MS(40);
    }
    for (int pm = 0x70; pm <= 0x8F; pm += 2) {
        lcd.set_contrast(static_cast<uint8_t>(pm));  // Set contrast, (value 0..255)
        DELAY_MS(40);
    }
    
    // --- Flash the inverse display twice ---
    for (int i = 0; i < 2; i++) {
        lcd.set_invert(true);                       // Invert display, (on)
        DELAY_MS(400);
        lcd.set_invert(false);                      // Invert display, (on)
        DELAY_MS(400);
    }
    
    // --- Hardware scroll ---
    // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
    // because RAM rows 128..159 are empty.
    for (uint8_t line = 0; line <= 32; line++) {
        lcd.set_scroll_line(line);                  // Scroll the image up, (line 0..159 - 2 * fixed)
        DELAY_MS(40);
    }
    for (int line = 32; line >= 0; line--) {
        lcd.set_scroll_line(static_cast<uint8_t>(line));  // Scroll the image up, (line 0..159 - 2 * fixed)
        DELAY_MS(40);
    }
    
    // --- Second screen in RAM ---
    // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
    lcd.set_write_pattern(1);                       // Select RAM screen to write, (pattern 0..3)
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_line(0, 0, 239, 127);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.draw_line(0, 127, 239, 0);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.show();                                     // Send framebuffer to the display, ()
    lcd.set_display_pattern(1);                     // Select displayed RAM screen, (pattern 0..3)
    DELAY_MS(2000);
    lcd.set_display_pattern(0);                     // Select displayed RAM screen, (pattern 0..3)
    lcd.set_write_pattern(0);                       // Select RAM screen to write, (pattern 0..3)
    DELAY_MS(1500);
    
    // --- Power down ---
    // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
    lcd.sleep();                                    // Enter sleep, ()
    PRINT("done\n");
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init();

    OutputPinSTM32Cube cs(GPIOB, GPIO_PIN_6);       // CS0 pin, (port, pin)
    OutputPinSTM32Cube dc(GPIOC, GPIO_PIN_7);       // CD (D/C#) pin, (port, pin)
    OutputPinSTM32Cube res(GPIOA, GPIO_PIN_9);      // RST pin, (port, pin)

    DisplayConnectionSTM32Cube connection(&hspi1, &cs, &dc, &res, nullptr);  // Create display connection, (hspi, cs, dc, res, busy=none)
    static UC1611sSpiFull<DisplayConnectionSTM32Cube> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128, topView=false, contrast=0x8F)
    if (!lcd.valid()) {
        printf("UC1611s init failed\r\n");
        while (true) HAL_Delay(1000);
    }
    run(lcd);
    while (true) {
        HAL_Delay(1000);
    }
}
