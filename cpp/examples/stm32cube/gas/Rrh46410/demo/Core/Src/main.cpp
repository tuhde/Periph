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
    sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → bool

    // --- Identity ---
    // Get Product ID returns 0x2310 and the firmware version; the tracking number
    // identifies the individual module.
    uint16_t pid = 0;
    uint8_t fw[3] = {0};
    sensor.product_id(pid, fw);                                    // Product ID and firmware, (productId&, firmware[3]) → bool
    printf("product 0x%04X, firmware %u.%u.%u\r\n", (unsigned)pid, fw[0], fw[1], fw[2]);
    uint8_t tn[6];
    sensor.tracking_number(tn);                                    // Tracking number, (out[6]) → bool
    printf("tracking %02X%02X%02X%02X%02X%02X, mode %d\r\n", tn[0], tn[1], tn[2], tn[3], tn[4], tn[5], sensor.get_mode()); // Operation mode, () → int

    // --- Humidity compensation ---
    // The MOx element reacts to humidity; feeding the real value improves the
    // results. The module assumes 50 %RH.
    sensor.set_humidity(42.0f);                                    // Ambient humidity, (percent %RH) → bool

    // --- Indoor air-quality monitor ---
    // A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
    // warm-up during which the values stay fixed.
    unsigned warmup = sensor.warmup_samples();                     // Warm-up length, (mode=-1) → uint8_t samples
    printf("sample period %u s, warm-up %u samples\r\n", (unsigned)sensor.sample_period(), warmup); // Sample period, (mode=-1) → float s
    RRH46410Result r;
    for (int i = 0; i < 20; i++) {
        if (!sensor.read(r)) continue;                             // Next sample, (result&) → bool
        print_result(r);
        printf("  rating %s, warm-up %u%%\r\n", RRH46410Full::air_quality_label(r.iaq), // UBA rating, (iaq) → const char*
              r.warmed_up ? 100u : (unsigned)r.sample * 100u / warmup);
    }

    // --- Interrupt instead of polling ---
    // With INT wired the driver reacts to its rising edge; otherwise poll_interrupt
    // compares the sample counter.
    g_sample_ready = false;
    sensor.on_interrupt(on_sample);                                // Subscribe to INT, (callback, intPin=nullptr) → void
    for (int i = 0; i < 100 && !g_sample_ready; i++) {
        if (sensor.poll_interrupt()) break;                        // New sample pending, () → bool
        HAL_Delay(100);
    }
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    if (sensor.wait_for_sample(4000) || sensor.read_results(r)) {  // Wait for a new sample, (timeoutMs) → bool; latest result, (result&) → bool
        printf("latest ");
        print_result(sensor.last_result());                        // Last decoded result, () → const RRH46410Result&
    }

    // --- Other operation modes ---
    // PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
    // one sample every 90 s. Each mode change restarts the warm-up.
    const uint8_t modes[2] = {RRH46410Full::MODE_PBAQ, RRH46410Full::MODE_ULP};
    for (int i = 0; i < 2; i++) {
        sensor.set_mode(modes[i]);                                 // Operation mode, (mode) → bool
        printf("%s: sample every %u s, warm-up %u samples\r\n", i == 0 ? "PBAQ" : "ULP",
              (unsigned)sensor.sample_period(), (unsigned)sensor.warmup_samples()); // Mode timing, (mode=-1) → float s / uint8_t
    }
    sensor.set_mode(RRH46410Full::MODE_PBAQ);                      // Operation mode, (mode) → bool
    if (sensor.read(r)) print_result(r);                           // Next sample, (result&) → bool

    // --- GPIOs ---
    // GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
    sensor.config_gpio(0x03);                                      // Both outputs, (directions) → bool
    for (int i = 0; i < 3; i++) {
        sensor.set_gpio(0x01);                                     // GPIO1 high, (mask) → bool
        HAL_Delay(200);
        sensor.clear_gpio(0x01);                                   // GPIO1 low, (mask) → bool
        HAL_Delay(200);
    }
    printf("GPIO levels 0x%02X, status 0x%02X\r\n", sensor.get_gpio(), sensor.status().raw); // GPIO levels, () → int; status, () → RRH46410Status
    uint8_t mode = 0;
    sensor.send_command(0x10, nullptr, 0, &mode, 1);               // Raw request, (command, params*, len, out*, responseLen) → int
    printf("raw mode %u\r\n", mode);

    // --- Wrap up ---
    // clean_sensor(true) runs the 1-minute cleaning once in the sensor's lifetime;
    // it is skipped here. Suspend stops measuring; reset restarts the module in SUSPEND.
    sensor.suspend();                                              // Suspend mode, () → bool
    sensor.reset();                                                // Reset, () → bool
    printf("mode after reset %d\r\n", sensor.get_mode());            // Operation mode, () → int
    while (1) {}
}
