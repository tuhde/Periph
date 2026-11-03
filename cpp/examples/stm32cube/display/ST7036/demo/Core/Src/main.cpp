// ST7036 demo example: EA DOGM162W-A (2 x 16) on a NUCLEO-F411RE.
//
// Demo: a two-line status display — a title line and a bar graph built from five custom characters that fills and empties, then a contrast sweep.
//
// Wiring (3.3 V module, 4-wire serial, PSB = GND, E = 3V3):
//   SI   = PA7 (D11, SPI1 MOSI)     SCL  = PA5 (D13, SPI1 SCK)
//   CSB  = PB6 (D10)                RS   = PC7 (D9)    (C/D: low = instruction, high = data)
//   RESET = PA9 (D8)                VDD  = 3V3         VSS = GND
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Progress goes to USART2 (ST-LINK virtual COM port, 115200 8N1).
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DisplayConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "ST7036.h"

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
// SPI1 is on APB2 (100 MHz): /128 gives 0.78 MHz; the ST7036 allows up to 5 MHz at 3.3 V (200 ns cycle).
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init();

    OutputPinSTM32Cube cs(GPIOB, GPIO_PIN_6);       // CS# (CSB) pin, (port, pin)
    OutputPinSTM32Cube dc(GPIOC, GPIO_PIN_7);       // D/C# (RS) pin, (port, pin)
    OutputPinSTM32Cube res(GPIOA, GPIO_PIN_9);      // RES# (RESET) pin, (port, pin)

    DisplayConnectionSTM32Cube connection(&hspi1, &cs, &dc, &res, nullptr);  // Create display connection, (hspi, cs, dc, res, busy=none)
    ST7036Full<DisplayConnectionSTM32Cube> lcd(connection);  // Create ST7036 driver, (connection, lines=2, supply=SUPPLY_3V3, contrast=-1 preset)
    typedef ST7036Full<DisplayConnectionSTM32Cube> Lcd;

    if (!lcd.valid()) {
        printf("ST7036 init failed\r\n");
        while (true) HAL_Delay(1000);
    }

    // --- Define the bar-graph glyphs ---
    // Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
    // can show 80 steps with the eight CGRAM slots.
    for (uint8_t g = 0; g < 5; g++) {
        uint8_t rows[8];
        uint8_t pattern = static_cast<uint8_t>(0x1F << (4 - g)) & 0x1F;
        for (uint8_t r = 0; r < 8; r++) rows[r] = (r == 0 || r == 7) ? 0 : pattern;
        lcd.create_char(g, rows);                   // Define user character, (index 0..7, rows[8])
    }

    // --- Static title line ---
    lcd.clear();                                    // Clear display, ()
    lcd.print_at(0, 0, "Periph ST7036");            // Write text, (col, row, text)

    // --- Animate a progress bar on line 2 ---
    // Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
    for (int step = 0; step <= 80; step++) {
        lcd.set_cursor(0, 1);                       // Move cursor, (col, row)
        for (int col = 0; col < 16; col++) {
            int fill = step - col * 5;              // pixel columns of this cell that are filled
            if (fill >= 5)      lcd.write_char(4);  // Write raw character code, (code)
            else if (fill > 0)  lcd.write_char(static_cast<uint8_t>(fill - 1));  // Write raw character code, (code)
            else                lcd.write_char(' ');  // Write raw character code, (code)
        }
        HAL_Delay(40);
    }
    HAL_Delay(1000);

    // --- Contrast sweep ---
    // The contrast is set once in normal use; sweeping it shows the usable range of the module.
    lcd.print_at(0, 0, "Contrast sweep ");          // Write text, (col, row, text)
    for (int c = 0x10; c <= 0x30; c++) {
        lcd.set_contrast(static_cast<uint8_t>(c));  // Set contrast, (value 0..63)
        HAL_Delay(80);
    }
    lcd.set_contrast(0x18);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 0, "Periph ST7036   ");         // Write text, (col, row, text)
    printf("done\r\n");

    while (true) {
        HAL_Delay(1000);
    }
}
