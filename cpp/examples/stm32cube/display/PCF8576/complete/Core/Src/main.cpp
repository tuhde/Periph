#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCF8576.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x70);
    PCF8576Full lcd(connection);

    HAL_Delay(2000);

    lcd.clear();                                         // Blank the display, () → void
                                                          // zeros all 40 columns of display RAM
    lcd.device_select(0);                                // Select device on the bus, (subaddress 0–7) → void
                                                          // sets the subaddress counter for cascaded use
    lcd.set_mode(PCF8576Full::BACKPLANES_4, PCF8576Full::BIAS_1_3);  // Set drive mode, (backplanes 1–4, bias 0/1) → void
                                                          // configures 1:4 multiplex with 1/3 bias
    lcd.set_blink(PCF8576Full::BLINK_2_HZ);              // Set blink frequency, (frequency 0–3) → void
                                                          // ~2 Hz blink for visual attention
    lcd.set_bank(PCF8576Full::BANK_0, PCF8576Full::BANK_0);  // Select RAM bank, (input_bank 0/1, output_bank 0/1) → void
                                                          // selects rows 0-1 for both input and output

    static const uint8_t digits[] = {5, 6, 7, 8};
    uint8_t out[4];
    for (uint8_t i = 0; i < 4; i++) {
        out[i] = PCF8576Full::SEVEN_SEG[digits[i]];     // Encode 7-segment digit, (digit 0–9) → uint8_t
    }
    lcd.write_raw(0, out, 4);                            // Write raw bytes, (address 0–39, data, len) → void
                                                          // sets data pointer to 0 and writes all four digits

    lcd.disable();                                        // Disable display output, () → void
                                                          // blanks the panel while keeping RAM contents
    lcd.enable();                                         // Enable display output, () → void
                                                          // resumes output from RAM with the prior configuration

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
