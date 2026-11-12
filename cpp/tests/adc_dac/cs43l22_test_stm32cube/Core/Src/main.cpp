#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "CS43L22.h"

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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, CS43L22Minimal::I2C_ADDRESS);
    CS43L22Minimal dac(connection);                     // Create CS43L22 driver, (connection, resPin=nullptr)
    check_true(dac.init(), "init");                     // Reset, verify ID, load defaults, () → bool
    dac.setVolume(-20.0f);                              // Set master volume, (db -102–12 dB) → void
    check_true(dac.getVolume() == -20.0f, "volume_roundtrip"); // Read master volume, () → float dB
    dac.mute(true);                                     // Mute headphones, (on=true) → void
    dac.mute(false);

    CS43L22Full full(connection);                       // Create CS43L22 Full driver, (connection, resPin=nullptr)
    check_true(full.init(), "full_init");
    uint8_t id, rev;
    full.readId(id, rev);                               // Read chip ID and revision, (&chipId, &revision) → void
    check_true(id == 0x1C, "chip_id");
    full.setVolumeChannels(-10.0f, -12.0f);             // Set per-channel master volume, (leftDb, rightDb) → void
    uint8_t v0 = 0, v1 = 0;
    full.readRegister(0x20, v0);                        // Read a raw register, (reg, &value) → bool
    full.readRegister(0x21, v1);
    check_true(v0 == 0xEC && v1 == 0xE8, "volume_channels");
    full.setHeadphoneGain(CS43L22Full::GAIN_1_000);     // Select headphone analog gain, (index 0–7) → bool
    check_true(full.headphoneGain() == 1.0f, "headphone_gain");
    full.setTone(6.0f, 6.0f);                           // Configure treble / bass shelves, (trebleDb, bassDb) → bool
    uint8_t tone = 0;
    full.readRegister(0x1F, tone);
    check_true(tone == 0x44, "tone");
    full.setLimiter(true, -6, -12);                     // Configure the peak limiter, (enabled, maxDb, cushionDb) → bool
    uint8_t lim = 0;
    full.readRegister(0x28, lim);
    check_true((lim & 0x80) == 0x80, "limiter");
    full.setLimiter(false);
    CS43L22SpeakerStatus sp = full.readSpeakerStatus(); // Read speaker status, () → CS43L22SpeakerStatus
    (void)sp;
    CS43L22Clocking clk;
    check_true(CS43L22Full::clockingFor(12288000, 48000.0f, clk) && clk.speed == 1, "clocking_for");
    full.stop();                                        // Power down the DAC, () → void
    uint8_t pwr = 0;
    full.readRegister(0x02, pwr);
    check_true(pwr == 0x9F, "stop_powers_down");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
