#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "PCF8575.h"

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

// The INT line is wired to PB3 (D3, EXTI3). InputPinSTM32Cube arms the EXTI line in
// onEdge(); the project must route the IRQ to the HAL and the HAL callback back to it.
extern "C" void EXTI3_IRQHandler(void) {
    HAL_GPIO_EXTI_IRQHandler(GPIO_PIN_3);
}

extern "C" void HAL_GPIO_EXTI_Callback(uint16_t gpioPin) {
    InputPinSTM32Cube::dispatchFromISR(gpioPin);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    InputPinSTM32Cube intPin(GPIOB, GPIO_PIN_3);               // Create INT pin, (port=GPIOB, pin=GPIO_PIN_3 / D3)
    I2CConnectionSTM32Cube connection(&hi2c1, 0x20, &intPin);       // Create I2C connection, (i2c, addr=0x20, intPin)
    PCF8575Full chip(connection, /*addr=*/0x20);

    PCF8575Full::IOExpanderPin p0 = chip.pin(0);                   // Get pin proxy, (n=0) → IOExpanderPin
    PCF8575Full::IOExpanderPin p8 = chip.pin(8);                   // Get pin proxy, (n=8) → IOExpanderPin

    p0.mode(OUTPUT);                                            // Set direction, (mode=OUTPUT) → void
                                                               // drives P00 low (safe initial state for output)
    p0.high();                                                  // Set high, () → void
                                                               // releases to quasi-input; not strong drive
    p0.low();                                                   // Drive low, () → void
                                                               // strong pull-down, up to 25 mA
    p0.toggle();                                                // Invert shadow bit, () → void

    uint8_t v = p0.read();                                      // Read actual level, () → uint8_t

    chip.write_port(0, 0b00001111);                             // Write Port 0, (port=0, mask=uint8_t) → void
    chip.write_port(1, 0b00001111);                             // Write Port 1, (port=1, mask=uint8_t) → void

    p8.mode(INPUT);                                             // Set direction, (mode=INPUT) → void
    uint8_t state = p8.read();                                  // Read actual level, () → uint8_t

    chip.onInterrupt([](uint16_t changed) {
        printf("changed: %u\r\n", changed);
    });                                                          // Subscribe to INT line, (callback) → void

    uint16_t changed = chip.pollInterrupt();                     // Read port and return 16-bit changed bitmask, () → uint16_t
    while (true) HAL_Delay(1000);
}
