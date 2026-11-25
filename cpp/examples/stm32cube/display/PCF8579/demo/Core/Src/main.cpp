#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCF8579.h"

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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x3C);
    // --- Initialise a 16 x 40 column driver ---
    // 1:16 multiplex gives 16 rows and 40 columns; the constructor clears the RAM
    // before switching the display on so no random start-up pattern is shown.
    PCF8579Full lcd(connection, 0, 16);                       // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false)

    // --- Draw a border and both diagonals ---
    // Lines and rectangles are rendered in the shadow buffer and sent as one
    // batch of changed bytes per run of columns.
    lcd.draw_rect(0, 0, 40, 16);                              // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
    lcd.draw_line(0, 0, 39, 15);                              // Draw line, (x0, y0, x1, y1, on=true) → void
    lcd.draw_line(0, 15, 39, 0);                              // Draw line, (x0, y0, x1, y1, on=true) → void

    // --- Flash the display twice with inverse video ---
    // Inverse video only changes how the RAM is shown, so the picture is kept.
    for (int i = 0; i < 2; i++) {
        lcd.set_display(PCF8579Full::DISPLAY_INVERSE);        // Set display status, (status 0–3) → bool
        HAL_Delay(400);
        lcd.set_display(PCF8579Full::DISPLAY_NORMAL);         // Set display status, (status 0–3) → bool
        HAL_Delay(400);
    }

    // --- Fill all four RAM banks with distinct patterns ---
    // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
    // buffers that the start bank can scroll into view.
    static const uint8_t patterns[4] = {0x55, 0x33, 0x0F, 0xFF};
    for (int bank = 0; bank < 4; bank++) {
        uint8_t col[40];
        for (int i = 0; i < 40; i++) col[i] = patterns[bank];
        lcd.write_ram(0, bank, col, sizeof(col));             // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    }

    // --- Scroll through the banks with the hardware start bank ---
    // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
    for (int bank = 0; bank < 4; bank++) {
        lcd.set_start_bank(bank);                             // Set start bank, (bank 0–3) → bool
        uint8_t first = 0;
        lcd.read_ram(0, bank, &first, 1);                     // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
        printf("first column byte 0x%02X\r\n", (unsigned)(first));
        HAL_Delay(1000);
    }

    // --- Read back the first 8 RAM columns of bank 0 ---
    // Writing a counting pattern and reading it back verifies the bus path.
    lcd.set_start_bank(0);                                    // Set start bank, (bank 0–3) → bool
    static const uint8_t scratch[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    lcd.write_ram(0, 0, scratch, sizeof(scratch));            // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    uint8_t back[8];
    lcd.read_ram(0, 0, back, sizeof(back));                   // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
    for (int i = 0; i < 8; i++) {
        printf("scratch byte 0x%02X\r\n", (unsigned)(back[i]));
    }

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) HAL_Delay(1000);
}
