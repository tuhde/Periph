// SSD1306 minimal example: SSD1306 128 x 64 OLED, STM32Cube HAL (NUCLEO-F411RE, I2C1)
// Wiring: SCL=PB8 (D15), SDA=PB9 (D14), A2=PB6, CD=PC7 (low), RST=PA9; A3 tied high -> 0x3C/0x3D; VDD = 3V3
// Module: 0.96" 128 x 64 OLED (SSD1306), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 128 x 64 panel.
#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "SSD1306.h"

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
    hi2c1.Init.ClockSpeed      = 400000;  // external pull-ups on SDA/SCL: 400 kHz
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

#define PRINT(...) printf(__VA_ARGS__)
#define DELAY_MS(ms) HAL_Delay(ms)

static void run(SSD1306Minimal& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 128; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(x, 63);                       // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x / 2));  // Set pixel, (x 0..127, y 0..63, on=true)
    }
    for (int16_t y = 0; y < 64; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(127, y);                      // Set pixel, (x 0..127, y 0..63, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube conn(&hi2c1, 0x3C);             // Create I2C connection, (hi2c, addr=0x3C)
    static SSD1306Minimal lcd(conn);  // Create SSD1306 driver, (connection, width=128, height=64, external_vcc=false)
    if (!lcd.valid()) {
        printf("SSD1306 init failed\r\n");
        while (true) HAL_Delay(1000);
    }
    run(lcd);
    while (true) {
        HAL_Delay(1000);
    }
}
