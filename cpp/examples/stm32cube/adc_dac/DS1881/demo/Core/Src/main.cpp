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

    // --- Fade the volume down and back up ---
    // Zero-crossing detection (on by default) moves the wiper only at a signal
    // zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
    // than the 50 ms maximum zero-crossing window.
    for (int step = 0; step < 42; step++) {
        int db = step <= 20 ? step * 2 : (41 - step) * 2;
        amp.setAttenuationBoth((float)db);              // Set both attenuations, (db0 dB, db1=db0 dB) → void
        uint8_t p0, p1, cfgByte;
        amp.readAll(p0, p1, cfgByte);                   // Read everything, (&position0, &position1, &config) → void
        printf("%2d dB -> positions %d %d\n", db, (int)p0, (int)p1);
        HAL_Delay(100);
    }

    // --- Mute and unmute one channel ---
    // Muting channel 0 leaves channel 1 playing; restoring writes the position again.
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
    printf("channel 0 muted: %d dB\n", (int)amp.getAttenuation(0)); // Read attenuation, (channel 0–1) → float dB
    HAL_Delay(1000);
    amp.setAttenuation(0, 0.0f);                        // Set attenuation, (channel 0–1, db dB) → void

    // --- Inspect the Option 2 attenuation table ---
    // Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
    // numeric value when the option changes, so re-set them afterwards.
    amp.setOption(2);                                   // Select option, (option 1–2) → void
    static const int table[] = {0, 12, 13, 24, 25, 32, 33};
    for (int i = 0; i < 7; i++) {
        printf("position %d -> %d dB\n", table[i], (int)amp.positionToDb(table[i])); // Table lookup, (position) → float dB
    }
    amp.setOption(1);                                   // Select option, (option 1–2) → void
    amp.setAttenuationBoth(10.0f);                      // Set both attenuations, (db0 dB, db1=db0 dB) → void
    DS1881Config cfg = amp.getConfig();                 // Read configuration, () → DS1881Config {option, zeroCrossing, nonvolatile, raw}
    printf("option %d zc %d nv %d\n", (int)cfg.option, (int)cfg.zeroCrossing, (int)cfg.nonvolatile);

    // --- Optionally persist the volume ---
    // Stored positions are restored at power-up. The EEPROM is rated for only
    // 50 000 writes, so this runs only when requested (--store on Linux).
    if (false) {
        printf("WARNING: EEPROM endurance is 50 000 writes\n");
        amp.setNonvolatile(true);                       // Select EEPROM storage, (enabled bool) → void
        amp.store();                                    // Store positions, () → void
        printf("store acknowledged: %d\n", (int)amp.waitReady(0.05f)); // Acknowledge-poll, (timeoutS=0.05 s) → bool
        amp.setNonvolatile(false);                      // Select EEPROM storage, (enabled bool) → void
    }
    while (1) {}
}
