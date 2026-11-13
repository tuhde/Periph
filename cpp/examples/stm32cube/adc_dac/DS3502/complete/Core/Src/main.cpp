#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "DS3502.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, DS3502Minimal::I2C_ADDRESS);
    DS3502Full pot(connection, 15.0f, 0.0f);            // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    pot.setWiper(100);                                  // Set wiper position, (code 0–127) → void
    // writes WR only; the EEPROM is not touched
    uint8_t w = pot.getWiper();                         // Read wiper position, () → uint8_t 0–127
    // reads WR; always the live wiper, never the IVR
    printf("wiper %d\n", (int)w);
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    // nearest code of V = V_RL + (V_RH − V_RL)·code/127
    float v = pot.getVoltage();                         // Read unloaded RW voltage, () → float V
    // evaluates the divider formula for the current code
    printf("voltage %.3f V\n", (double)v);
    pot.setRatio(0.25f);                                // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 127)
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    // code / 127
    printf("ratio %.4f\n", (double)ratio);
    float rl = pot.getResistance('L');                  // Read wiper-to-RL resistance, (terminal='L') → float Ω
    // nominal, RTOTAL = 10 kΩ ±20 %
    float rh = pot.getResistance('H');                  // Read wiper-to-RH resistance, (terminal='L') → float Ω
    printf("R_WL %.1f ohm, R_WH %.1f ohm\n", (double)rl, (double)rh);
    uint8_t n = pot.increment(5);                       // Step the wiper, (steps=1) → uint8_t 0–127
    // read, add, clamp, write; returns the new code
    printf("incremented to %d\n", (int)n);
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    // true if writes to 0x00 also program the EEPROM
    printf("eeprom mode %d\n", (int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void
    // CR = 0x80; true would write CR = 0x00
    bool stored = pot.storeWiper(64);                   // Store power-up position, (code=-1) → bool
    // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
    printf("stored %d\n", (int)stored);
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    // false if the chip keeps NACKing
    printf("ready %d\n", (int)ready);
    pot.setEndpoints(12.0f, 2.0f);                      // Change RH / RL voltages, (vHigh V, vLow=0.0 V) → void
    // affects the conversions only; no bus traffic
    while (1) {}
}
