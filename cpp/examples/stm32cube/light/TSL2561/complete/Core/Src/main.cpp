#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "TSL2561.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, TSL2561Minimal::I2C_ADDRESS);
    TSL2561Full sensor(connection);                     // Create TSL2561 Full driver, (connection)
    bool ok = sensor.init();                            // Power up, 1× gain, 402 ms, wait one integration, () → bool
    // CONTROL ← 0x03 and read back; lastError() = ERR_INIT if it does not read 0x03
    int id = sensor.readId();                           // Read the ID register, () → int
    // bits 7:4 = 1 for the TSL2561, bits 3:0 revision; -1 on a bus error
    printf("init %d id 0x%02X\n", (int)ok, id);
    float lx = sensor.readLux();                        // Read illuminance, () → float lux
    // NAN with lastError() = ERR_SATURATED on a saturated channel
    printf("lux %.1f error %d\n", (double)lx, sensor.lastError());  // Error of the last operation, () → int
    uint16_t ch0 = 0, ch1 = 0;
    sensor.readRaw(ch0, ch1);                           // Read both channels, (ch0&, ch1&) → bool
    // one block read: ch0 visible+IR, ch1 IR
    sensor.readLuxRaw(lx, ch0, ch1);                    // Lux plus counts, (lux&, ch0&, ch1&) → bool
    printf("lux %.1f ch0 %u ch1 %u\n", (double)lx, (unsigned)ch0, (unsigned)ch1);
    float calc = TSL2561Minimal::calculateLux(ch0, ch1, 1, 402, TSL2561Minimal::PACKAGE_T);  // Convert counts, (ch0, ch1, gain, integrationMs, package) → float lux
    printf("calculated %.1f lx\n", (double)calc);

    sensor.setGain(16);                                 // Set ADC gain, (gain 1|16) → bool
    // 16× for dim light; saturates indoors at ~1–2 klx
    sensor.setIntegrationTime(101);                     // Set integration time, (ms 14|101|402) → bool
    // the conversion in progress keeps the old setting
    printf("gain %u integration %u ms\n", (unsigned)sensor.getGain(), (unsigned)sensor.getIntegrationTime());  // Current settings, () → uint8_t, () → uint16_t ms
    HAL_Delay(250);
    sensor.setPackage(TSL2561Minimal::PACKAGE_CS);      // Select lux coefficients, (package) → void
    // T, FN and CL share one set; CS has its own
    printf("lux CS %.1f\n", (double)sensor.readLux());
    sensor.setPackage(TSL2561Minimal::PACKAGE_T);
    lx = sensor.autoRange();                            // Auto-range and read lux, () → float lux
    // 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100
    printf("auto %.1f lx\n", (double)lx);

    sensor.startManual();                               // Start a host-timed integration, () → bool
    // INTEG = 11, MANUAL = 1
    HAL_Delay(200);
    sensor.stopManual();                                // Stop the integration, () → bool
    // INTEG = 11, MANUAL = 0; counts are ready
    sensor.readRaw(ch0, ch1);
    printf("manual ch0 %u ch1 %u\n", (unsigned)ch0, (unsigned)ch1);
    sensor.setIntegrationTime(402);
    sensor.setGain(1);

    sensor.setThresholds(100, 20000);                   // Set channel-0 window, (low, high counts) → bool
    // word writes of 0x2–0x3 and 0x4–0x5
    uint16_t low = 0, high = 0;
    sensor.getThresholds(low, high);                    // Read the window, (low&, high&) → bool
    printf("window %u .. %u\n", (unsigned)low, (unsigned)high);
    sensor.configureInterrupt(1, 2);                    // Interrupt mode, (mode 0|1|2, persist 0–15) → bool
    // level interrupt after 2 cycles outside the window
    bool outside = sensor.pollInterrupt();              // Check ch0 against the window, () → bool
    // does not clear the chip's interrupt
    printf("outside %d\n", (int)outside);
    sensor.clearInterrupt();                            // Release a level interrupt, () → bool
    // command byte 0xC0
    sensor.onInterrupt([](uint16_t c0) { (void)c0; });  // Subscribe, (callback(ch0), intPin=nullptr) → void
    // falling edge on INT; call service() periodically without a pin
    sensor.service();                                   // Poll once and dispatch the callback, () → void
    sensor.offInterrupt();                              // Unsubscribe, () → void
    sensor.configureInterrupt(0, 0);

    bool powered = sensor.isPowered();                  // Power state, () → bool
    // CONTROL reads 0x03 when powered
    sensor.powerDown();                                 // Power down, () → bool
    // CONTROL = 0x00; registers keep their values
    sensor.powerUp();                                   // Power up, () → bool
    // CONTROL = 0x03; integration restarts
    printf("powered %d\n", (int)powered);
    while (1) {}
}
