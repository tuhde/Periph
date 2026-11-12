#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "DS1881.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, DS1881Full::I2C_ADDRESS);
    DS1881Full amp(connection);                         // Create DS1881 Full driver, (connection)
    amp.init();                                         // Apply defaults, () → void
                                                        // waits 1 ms, rewrites the config only if it differs

    amp.setAttenuation(0, 12.0f);                       // Set attenuation, (channel 0–1, db dB) → void
                                                        // nearest position, one command byte
    float a = amp.getAttenuation(0);                    // Read attenuation, (channel 0–1) → float dB
                                                        // three-byte round-robin read, position in dB
    printf("attenuation %d dB\n", (int)a);
    amp.setPosition(1, 30);                             // Set raw wiper position, (channel 0–1, position 0–63) → void
                                                        // position 63 is mute in Option 1
    printf("position %d\n", (int)amp.getPosition(1));   // Read wiper position, (channel 0–1) → uint8_t
                                                        // low six bits of the read-back byte
    amp.mute(1);                                        // Mute channel, (channel 0–1) → void
                                                        // writes the mute position (63)

    amp.setBoth(5, 25);                                 // Set both wipers, (position0 0–63, position1 0–63) → void
                                                        // one transaction, both wipers update together
    amp.setAttenuationBoth(20.0f);                      // Set both attenuations, (db0 dB, db1=db0 dB) → void
                                                        // db1 defaults to db0 (stereo volume)
    uint8_t p0, p1, cfgByte;
    amp.readAll(p0, p1, cfgByte);                       // Read everything, (&position0, &position1, &config) → void
                                                        // one three-byte round-robin read
    printf("positions %d %d config 0x%02X\n", (int)p0, (int)p1, (int)cfgByte);

    amp.configure(2, true, false);                      // Configure, (option=1 1–2, zeroCrossing=true, nonvolatile=false) → void
                                                        // writes the config byte only if it changed
    DS1881Config cfg = amp.getConfig();                 // Read configuration, () → DS1881Config {option, zeroCrossing, nonvolatile, raw}
                                                        // fresh read; refreshes the driver's table
    printf("option %d zc %d nv %d\n", (int)cfg.option, (int)cfg.zeroCrossing, (int)cfg.nonvolatile);
    printf("position 13 = %d dB, max position %d\n", (int)amp.positionToDb(13), (int)amp.maxPosition()); // Table lookup, (position) → float dB; Last non-mute position, () → uint8_t
                                                        // Option 2: 13 → 14 dB, max position 32
    printf("39 dB = position %d\n", (int)amp.dbToPosition(39.0f)); // Nearest position, (db dB) → uint8_t
                                                        // 70 dB and above map to mute
    amp.setOption(1);                                   // Select option, (option 1–2) → void
                                                        // positions are not converted
    amp.setZeroCrossing(false);                         // Set zero-crossing, (enabled bool) → void
                                                        // ZC bit of the configuration
    amp.setZeroCrossing(true);                          // Set zero-crossing, (enabled bool) → void
                                                        // clickless updates again
    bool ready = amp.waitReady(0.05f);                  // Acknowledge-poll, (timeoutS=0.05 s) → bool
                                                        // true once the chip ACKs its address
    printf("ready %d\n", (int)ready);

    amp.setNonvolatile(true);                           // Select EEPROM storage, (enabled bool) → void
                                                        // V/NV bit; every wiper write now wears the EEPROM (50 000 writes)
    amp.store();                                        // Store positions, () → void
                                                        // rewrites both wipers and polls for completion
    amp.setNonvolatile(false);                          // Select EEPROM storage, (enabled bool) → void
                                                        // back to volatile wipers
    HAL_Delay(1000);
    amp.reset();                                        // Reset, () → void
                                                        // volatile, zero-crossing, Option 1, both wipers muted
    while (1) {}
}
