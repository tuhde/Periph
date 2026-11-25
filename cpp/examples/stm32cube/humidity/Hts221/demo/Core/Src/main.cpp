#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Hts221.h"

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

static void on_drdy(uint8_t status) {
    printf("DRDY status 0x%02X\r\n", (unsigned)(status));
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x5F);
    HTS221Full sensor(connection);                                  // Create HTS221 driver, (connection) → void
    bool ok = sensor.init();                                        // Read calibration and start one-shot mode, () → bool
    float t = 0, rh = 0, hz = 0;
    int16_t raw = 0;
    uint16_t ts = 0, hs = 0;
    HTS221Calibration cal = {};

    // --- Identify the chip and show the factory calibration ---
    // Every HTS221 stores its own two-point calibration; init() read it and
    // every conversion uses it.
    printf("who am i 0x%02X\r\n", (unsigned)(sensor.who_am_i()));   // Read WHO_AM_I, () → uint8_t (0xBC)
    sensor.read_calibration(cal);                                   // Read calibration, (cal) → void
    printf("H0_rH %.1f H1_rH %.1f T0 %.2f C T1 %.2f C\r\n", (double)(cal.h0_rh), (double)(cal.h1_rh), (double)(cal.t0_degc), (double)(cal.t1_degc));

    // --- One-shot hygrometer: 10 readings, 2 s apart ---
    // Each read() starts a conversion; the dew point shows what the pair
    // means for condensation risk.
    for (int i = 0; i < 10; i++) {
        ok = sensor.read(t, rh);                                    // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    printf("    %.2f C  %.1f %%rH  dew %.2f C\r\n", (double)(t), (double)(rh), (double)(sensor.dew_point()));// Compute dew point, () → float °C
        ok = sensor.read_raw_humidity(raw);                         // Read raw humidity, (raw) → bool
    printf("    raw %ld %ld\r\n", (long)(raw), (long)(sensor.read_humidity()));// Read humidity only, () → float %rH
    printf("    %.2f C\r\n", (double)(sensor.read_temperature()));  // Read temperature only, () → float °C
        ok = sensor.read_raw_temperature(raw);                      // Read raw temperature, (raw) → bool
    HAL_Delay(2000);
    }

    // --- Continuous sampling at 1 Hz and 12.5 Hz ---
    // data_ready() paces the loop; the sample count per second follows the rate.
    ok = sensor.set_block_data_update(true);                        // Set block data update, (enabled) → bool
    for (int r = 0; r < 2; r++) {
        ok = sensor.set_output_data_rate(r == 0 ? 1.0f : 12.5f);    // Set output data rate, (hz 0|1|7|12.5) → bool
        ok = sensor.get_output_data_rate(hz);                       // Get output data rate, (hz) → bool
    printf("    rate %.1f Hz\r\n", (double)(hz));
        int count = 0;
        for (int ms = 0; ms < 3000; ms += 5) {
            if (sensor.data_ready()) {                              // Check data available, () → bool
                ok = sensor.read_continuous(t, rh);                 // Read latest sample, (temperature_c, humidity_pct) → bool
                count++;
            }
            HAL_Delay(5);
        }
    printf("    %.1f samples/s\r\n", (double)(count / 3.0f));
    }
    ok = sensor.set_output_data_rate(0);                            // Set output data rate, (hz 0|1|7|12.5) → bool

    // --- Noise at lowest and highest averaging ---
    // Ten one-shot samples each; the spread of the raw humidity shrinks as
    // the averaging grows.
    for (int a = 0; a < 2; a++) {
        ok = sensor.set_averaging(a == 0 ? 2 : 256, a == 0 ? 4 : 512);// Set averaging, (temperature_samples, humidity_samples) → bool
        ok = sensor.get_averaging(ts, hs);                          // Get averaging, (temperature_samples, humidity_samples) → bool
    printf("    averaging %lu %lu\r\n", (unsigned long)(ts), (unsigned long)(hs));
        int lo = 32767, hi = -32768;
        for (int i = 0; i < 10; i++) {
            ok = sensor.read_raw_humidity(raw);                     // Read raw humidity, (raw) → bool
            if (raw < lo) lo = raw;
            if (raw > hi) hi = raw;
        }
    printf("    raw humidity spread %ld\r\n", (long)(hi - lo));
    }
    ok = sensor.set_averaging(16, 32);                              // Set averaging, (temperature_samples, humidity_samples) → bool

    // --- Heater: drive off condensation for 10 s ---
    // Humidity falls and temperature rises while the heater runs; measure
    // before and after, never during, then let the sensor cool.
    float t0 = 0, rh0 = 0;
    ok = sensor.read(t0, rh0);                                      // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    ok = sensor.set_heater(true);                                   // Switch the heater, (on) → bool
    printf("heater on %lu\r\n", (unsigned long)(sensor.is_heater_on()));// Heater state, () → bool
    HAL_Delay(10000);
    ok = sensor.set_heater(false);                                  // Switch the heater, (on) → bool
    ok = sensor.read(t, rh);                                        // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    printf("before %.2f C %.1f %%rH, after %.2f C %.1f %%rH\r\n", (double)(t0), (double)(rh0), (double)(t), (double)(rh));

    // --- Data-ready notification for 5 s ---
    // Uses the DRDY pin when the connection has one, otherwise polls the status.
    ok = sensor.configure_drdy(true, true, false);                  // Configure DRDY pin, (enabled, active_high=true, open_drain=false) → bool
    ok = sensor.set_output_data_rate(1);                            // Set output data rate, (hz 0|1|7|12.5) → bool
    if (!sensor.on_interrupt(on_drdy)) {                            // Subscribe to data-ready, (callback, intPin=nullptr) → bool
        for (int i = 0; i < 5; i++) {
    printf("        DRDY status 0x%02X\r\n", (unsigned)(sensor.poll_interrupt()));// Poll interrupt status, () → uint8_t
            HAL_Delay(1000);
        }
    }
    else {
            HAL_Delay(5000);
        sensor.off_interrupt();                                     // Unsubscribe, () → void
    }
    ok = sensor.configure_drdy(false);
    ok = sensor.set_output_data_rate(0);

    // --- Power cycle and reload ---
    ok = sensor.power_down();                                       // Power down, () → bool
    ok = sensor.power_up();                                         // Power up, () → bool
    ok = sensor.reboot();                                           // Reload trimming, () → bool
    ok = sensor.read(t, rh);
    printf("done %.2f C ok=%lu\r\n", (double)(t), (unsigned long)(ok));
    while (true) { HAL_Delay(1000); }
}
