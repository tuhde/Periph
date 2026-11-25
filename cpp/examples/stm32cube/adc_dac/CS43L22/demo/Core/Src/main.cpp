#include <stdio.h>
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, CS43L22Full::I2C_ADDRESS);
    CS43L22Full dac(connection);                     // Create CS43L22 Full driver, (connection, resPin=nullptr)

    // --- Bring up the headphone amplifier ---
    // The control port only configures the codec; MCLK = 12.288 MHz and the 48 kHz
    // I2S stream come from the board's I2S master. init() loads the I2S-slave
    // defaults while the chip is powered down; start() powers it up once MCLK runs.
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
    dac.setVolume(-60.0f);                           // Set master volume, (db -102–12 dB) → void
    dac.start();                                     // Power up the DAC once MCLK is running, () → void

    // --- Wait for the serial port to lock ---
    // SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
    // reading the status register clears the flags.
    for (int i = 0; i < 50; i++) {
        CS43L22Status st = dac.readStatus();         // Read status flags (clears them), () → CS43L22Status
        if (!st.clockError) break;
        HAL_Delay(100);
    }

    // --- Ramp the volume up ---
    // 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
    // smooths each step further.
    for (int vol = -60; vol <= 0; vol += 2) {
        dac.setVolume((float)vol);                   // Set master volume, (db -102–12 dB) → void
        HAL_Delay(100);
    }

    // --- Shape the tone and protect the headphones ---
    // +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
    // loud peaks from clipping the amplifier.
    dac.setTone(6.0f, 6.0f);                         // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner=0, bassCorner=0, enabled=true) → bool
    dac.setLimiter(true, -6, -12);                   // Configure the peak limiter, (enabled, maxDb=0, cushionDb=0, release=0x3F, attack=0, limitAll=true, softRampDisable=false, zeroCrossDisable=false) → bool

    // --- Play a single beep ---
    // The beep generator needs no audio data; it mixes into the output once MCLK
    // and LRCK are running. Frequency code 0x1 is 521.74 Hz.
    dac.setBeep(0x1, 2, 0, -6.0f, CS43L22Full::BEEP_SINGLE); // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix=true) → bool
    HAL_Delay(1500);
    dac.stopBeep();                                  // Stop the beep generator, () → void

    // --- Power down safely ---
    // stop() mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
    // only then may the application remove MCLK.
    dac.stop();                                      // Power down the DAC, () → void
    printf("MCLK may now be removed\n");
    while (1) {}
}
