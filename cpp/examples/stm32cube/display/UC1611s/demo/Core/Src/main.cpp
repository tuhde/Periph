// UC1611s demo example: EA DOGXL240-7, STM32Cube HAL (NUCLEO-F411RE, I2C1)
// Wiring: SCL=PB8 (D15), SDA=PB9 (D14), A2=PB6, CD=PC7 (low), RST=PA9; A3 tied high -> 0x3C/0x3D; VDD = 3V3
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), I2C: BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 4.7 k to 3.3 V.
// Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "UC1611s.h"

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
    hi2c1.Init.ClockSpeed      = 100000;  // internal pull-ups only: 100 kHz
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

// Module control pins (NUCLEO-F411RE): A2 = PB6, CD = PC7 (unused in I2C mode, held low), RST = PA9.
// A3 is tied high on the module, so A2 low selects command address 0x3C / data address 0x3D (A2 high: 0x3E / 0x3F).
static void module_pins_init(void) {
    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
    gpioInit.Pull  = GPIO_NOPULL;
    gpioInit.Speed = GPIO_SPEED_FREQ_LOW;
    gpioInit.Pin = GPIO_PIN_6;                          // A2
    HAL_GPIO_Init(GPIOB, &gpioInit);
    HAL_GPIO_WritePin(GPIOB, GPIO_PIN_6, GPIO_PIN_RESET);
    gpioInit.Pin = GPIO_PIN_7;                          // CD
    HAL_GPIO_Init(GPIOC, &gpioInit);
    HAL_GPIO_WritePin(GPIOC, GPIO_PIN_7, GPIO_PIN_RESET);
    gpioInit.Pin = GPIO_PIN_9;                          // RST (active low): pulse, then release
    HAL_GPIO_Init(GPIOA, &gpioInit);
    HAL_GPIO_WritePin(GPIOA, GPIO_PIN_9, GPIO_PIN_RESET);
    HAL_Delay(2);
    HAL_GPIO_WritePin(GPIOA, GPIO_PIN_9, GPIO_PIN_SET);
}

#define PRINT(...) printf(__VA_ARGS__)
#define DELAY_MS(ms) HAL_Delay(ms)

static void run(UC1611sFull& lcd) {
    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear();                                    // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 240, 128);                  // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()
    
    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second even at 400 kHz, instead of the 8 of a full-frame show().
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
    PRINT("done (I2C retries %lu, unrecovered %lu)\r\n", static_cast<unsigned long>(lcd.retry_count()), static_cast<unsigned long>(lcd.error_count()));
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    module_pins_init();

    I2CConnectionSTM32Cube cmdConn(&hi2c1, 0x3C);          // Create command connection, (hi2c, addr=0x3C)
    I2CConnectionSTM32Cube dataConn(&hi2c1, 0x3D);         // Create data connection, (hi2c, addr=0x3D)
    cmdConn.setTimeout(50);                                // HAL timeout, (ms=50): give up quickly, the driver retries
    dataConn.setTimeout(50);
    static UC1611sFull lcd(cmdConn, dataConn);  // Create UC1611s driver, (command, data, width=240, height=128)
    if (!lcd.valid()) {
        printf("UC1611s init failed\r\n");
        while (true) HAL_Delay(1000);
    }
    run(lcd);
    while (true) {
        HAL_Delay(1000);
    }
}
