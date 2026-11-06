#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "AD5243.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, AD5243Minimal::I2C_ADDRESS);
    AD5243Full pot(connection);                         // Create AD5243 Full driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    pot.setWiper(0, 100);                               // Set wiper position, (channel 0–1, code 0–255) → bool
    // writes [instruction, code]; the instruction byte selects the channel
    uint8_t w = pot.getWiper(0);                        // Read wiper position, (channel 0–1) → uint8_t 0–255
    // sends the instruction alone, then reads one byte
    printf("wiper %d\n", (int)w);
    pot.setResistance(0, 5000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    // inverts R = D/256·RAB + 2·RW to the nearest code
    float r = pot.getResistance(0);                     // Read W–B resistance, (channel 0–1) → float Ω
    // evaluates D/256·RAB + 2·RW for the current code
    printf("R_WB %.1f ohm\n", (double)r);
    pot.setBoth(32, 224);                               // Write both wipers, (code1 0–255, code2 0–255) → void
    // two transactions, channel 0 then channel 1
    pot.setRatio(0, 0.25f);                             // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    // code = round(ratio × 256), clamped to 255
    float ratio = pot.getRatio(0);                      // Read divider ratio, (channel 0–1) → float
    // code / 256
    printf("ratio %.4f\n", (double)ratio);
    pot.setResistanceWa(1, 3000.0f);                    // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    // rheostat between W and A with B open
    float rwa = pot.getResistanceWa(1);                 // Read W–A resistance, (channel 0–1) → float Ω
    // (256 − D)/256·RAB + 2·RW
    printf("R_WA %.1f ohm\n", (double)rwa);
    uint8_t n = pot.increment(0, 5);                    // Step the wiper, (channel 0–1, steps=1) → uint8_t 0–255
    // read, add, clamp, write; returns the new code
    printf("incremented to %d\n", (int)n);
    pot.shutdown(1);                                    // Shut channel down, (channel 0–1) → void
    // SD = 1: A opens, wiper shorts to B, RDAC kept
    bool sd = pot.isShutdown(1);                        // Query shutdown state, (channel 0–1) → bool
    // driver-tracked; the chip cannot report it
    printf("shutdown %d\n", (int)sd);
    HAL_Delay(1000);
    pot.resume(1);                                      // Leave shutdown, (channel 0–1) → void
    // rewrites the kept code with SD = 0
    pot.setRab(50000.0f, 120.0f);                       // Change resistance model, (rAb Ω, rWiper=-1 Ω) → void
    // affects conversions only; no bus traffic
    pot.reset();                                        // Restore power-up preset, () → void
    // writes 0x80 to both channels with SD = 0
    while (1) {}
}
