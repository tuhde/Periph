#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Rrh46410.h"

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

static void print_result(const RRH46410Result& r) {
    unsigned long tvoc = (unsigned long)(r.tvoc_mg_m3 * 1000.0f + 0.5f);
    unsigned long etoh = (unsigned long)(r.etoh_ppm * 1000.0f + 0.5f);
    unsigned iaq = (unsigned)(r.iaq * 10.0f + 0.5f);
    printf("sample %u  IAQ %u.%u  TVOC %lu.%03lu mg/m3  EtOH %lu.%03lu ppm  eCO2 %u ppm  rel %u  %s\r\n",
          (unsigned)r.sample, iaq / 10, iaq % 10, tvoc / 1000, tvoc % 1000, etoh / 1000, etoh % 1000,
          (unsigned)r.eco2_ppm, (unsigned)r.relative_iaq, r.warmed_up ? "ok" : "warming up");
}

static volatile bool g_sample_ready = false;
static void on_sample() { g_sample_ready = true; }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x38);
    RRH46410Full sensor(connection);                               // Create RRH46410 driver, (connection) → void
    // The module has the fixed address 0x38 and stretches the I²C clock.
    sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → bool
    // Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).

    uint16_t pid = 0;
    uint8_t fw[3] = {0};
    sensor.product_id(pid, fw);                                    // Product ID and firmware, (productId&, firmware[3]) → bool
    // The product ID is always 0x2310.
    printf("product 0x%04X firmware %u.%u.%u\r\n", (unsigned)pid, fw[0], fw[1], fw[2]);
    uint8_t tn[6];
    sensor.tracking_number(tn);                                    // ASIC tracking number, (out[6]) → bool
    // Unique per module.
    printf("tracking %02X%02X%02X%02X%02X%02X\r\n", tn[0], tn[1], tn[2], tn[3], tn[4], tn[5]);
    printf("mode %d\r\n", sensor.get_mode());                        // Operation mode, () → int
    // 1 = IAQ 2nd Gen after init.

    sensor.set_humidity(45.0f);                                    // Ambient humidity, (percent %RH) → bool
    // Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
    RRH46410Result r;
    if (sensor.read(r)) print_result(r);                           // Next sample, (result&) → bool
    // Waits for the sample counter to change; values are fixed during the warm-up.
    printf("new sample %u\r\n", (unsigned)sensor.wait_for_sample(4000)); // Wait for a new sample, (timeoutMs) → bool
    // True when the counter changed (or INT went high) within the timeout.
    if (sensor.read_results(r)) print_result(r);                   // Latest result, (result&) → bool
    // Does not wait; fails with ERR_NOT_READY before the first sample of the mode.
    print_result(sensor.last_result());                            // Last decoded result, () → const RRH46410Result&
    // The sample stored by read(), read_results() or wait_for_sample().
    printf("%s\r\n", RRH46410Full::air_quality_label(2.4f));        // UBA rating, (iaq) → const char*
    // "very good", "good", "medium", "poor" or "bad".
    printf("period %u s warm-up %u\r\n", (unsigned)sensor.sample_period(), (unsigned)sensor.warmup_samples()); // Mode timing, (mode=-1) → float s / uint8_t
    // 3 s and 100 samples in IAQ 2nd Gen mode.
    RRH46410Status st = sensor.status();                           // Last status byte, () → RRH46410Status
    // Error bits and the operation-mode error code (0xA = sensor damaged).
    printf("status 0x%02X mode error %u\r\n", st.raw, st.mode_error);

    g_sample_ready = false;
    sensor.on_interrupt(on_sample);                                // Subscribe to INT, (callback, intPin=nullptr) → void
    // Rising edge on INT; without a pin nothing is delivered and poll_interrupt() is used.
    for (int i = 0; i < 40 && !g_sample_ready; i++) {
        if (sensor.poll_interrupt()) break;                        // New sample pending, () → bool
        // INT level if wired, otherwise a counter comparison.
        HAL_Delay(100);
    }
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    // Detaches the edge handler.

    sensor.set_mode(RRH46410Full::MODE_PBAQ);                      // Operation mode, (mode) → bool
    // PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
    if (sensor.read(r)) print_result(r);                           // Next sample, (result&) → bool
    // PBAQ results have a finer resolution (raw / 1000); IAQ and eCO2 are 0.
    sensor.set_mode(RRH46410Full::MODE_ULP);                       // Operation mode, (mode) → bool
    // ULP: one sample every 90 s at 0.23 mW.
    printf("period %u s warm-up %u\r\n", (unsigned)sensor.sample_period(), (unsigned)sensor.warmup_samples()); // Mode timing, (mode=-1) → float s / uint8_t
    // 90 s and 10 samples.

    sensor.config_gpio(0x03);                                      // GPIO directions, (directions) → bool
    // Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
    sensor.set_gpio(0x01);                                         // Drive high, (mask) → bool
    // GPIO1 high.
    printf("gpio 0x%02X\r\n", sensor.get_gpio());                    // GPIO levels, () → int
    // Bits 0–3 = GPIO1–GPIO4.
    sensor.clear_gpio(0x01);                                       // Drive low, (mask) → bool
    // GPIO1 low.

    uint8_t mode = 0;
    sensor.send_command(0x10, nullptr, 0, &mode, 1);               // Raw request, (command, params*=nullptr, len=0, out*=nullptr, responseLen=0) → int
    // Checksums added and verified; here Get Operation Mode.
    printf("raw mode %u\r\n", mode);
    // sensor.clean_sensor(true);                                  // One-time cleaning, (confirm=false) → bool
    // Runs for one minute, once in the sensor's lifetime; left disabled here.
    sensor.suspend();                                              // Suspend mode, () → bool
    // Stops measuring, about 0.7 µA.
    sensor.reset();                                                // Reset, () → bool
    // Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
    printf("done (error %d)\r\n", sensor.last_error());              // Reason of the last failure, () → int
    while (1) {}
}
