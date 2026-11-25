// ST7036 complete example: EA DOGM162W-A (2 x 16) on a NUCLEO-F411RE.
//
// Complete: every Full method — text, cursor and blink, entry mode, shift, contrast, booster, follower, bias, custom characters, display on/off.
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

    lcd.clear();                                    // Clear display, ()
                                                    // writes spaces to all of DDRAM, cursor to home (2 ms)
    lcd.print_at(0, 0, "Complete example");         // Write text, (col, row, text)
    lcd.set_cursor(0, 1);                           // Move cursor, (col, row)
    lcd.write("Cursor + blink");                    // Write text at the cursor, (text)
    lcd.cursor(true, true);                         // Cursor, (on, blink=false)
    HAL_Delay(2000);
    lcd.cursor(false, false);                       // Cursor, (on, blink=false)

    static const uint8_t heart[8] = {0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00};
    static const uint8_t arrow[8] = {0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00};
    lcd.create_char(0, heart);                      // Define user character, (index 0..7, rows[8])
                                                    // 8 rows of 5 bits into CGRAM, DDRAM address restored
    lcd.create_char(1, arrow);                      // Define user character, (index 0..7, rows[8])
    lcd.clear();                                    // Clear display, ()
    lcd.set_cursor(0, 0);                           // Move cursor, (col, row)
    lcd.write("I ");                                // Write text at the cursor, (text)
    lcd.write_char(0);                              // Write raw character code, (code)
    lcd.write(" custom ");                          // Write text at the cursor, (text)
    lcd.write_char(1);                              // Write raw character code, (code)
    HAL_Delay(2000);

    lcd.set_contrast(0x08);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 1, "low contrast");             // Write text, (col, row, text)
    HAL_Delay(1500);
    lcd.set_contrast(0x30);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 1, "high contrast");            // Write text, (col, row, text)
    HAL_Delay(1500);
    lcd.set_contrast(0x18);                         // Set contrast, (value 0..63)
                                                    // 0x18 is the DOGM162 3.3 V preset
    lcd.set_bias(false);                            // LCD bias, (quarter=false) 1/5 bias
    lcd.set_follower(true, 5);                      // Voltage follower, (on, ratio 0..7) 1 + Rb/Ra = 2.5
    lcd.set_booster(true);                          // Voltage booster, (on)

    lcd.clear();                                    // Clear display, ()
    lcd.set_entry_mode(true, false);                // Entry mode, (increment, shift=false)
    lcd.print_at(0, 0, "Shift display ->");         // Write text, (col, row, text)
    lcd.print_at(0, 1, "0123456789ABCDEF");         // Write text, (col, row, text)
    HAL_Delay(1000);
    for (int i = 0; i < 6; i++) {
        lcd.shift_display(true);                    // Shift display, (right)
        HAL_Delay(300);
    }
    lcd.return_home();                              // Return home, () — undoes the display shift
    lcd.shift_cursor(true);                         // Shift cursor, (right)
    lcd.set_ddram_address(0x40);                    // Set DDRAM address, (address)
    HAL_Delay(1000);

    lcd.display(false);                             // Display on/off, (on)
    HAL_Delay(800);
    lcd.display(true);                              // Display on/off, (on)
    printf("done\r\n");

    while (true) {
        HAL_Delay(1000);
    }
}
