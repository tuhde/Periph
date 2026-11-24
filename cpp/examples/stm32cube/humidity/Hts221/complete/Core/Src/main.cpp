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
                                                                    // decodes the per-device two-point coefficients
    float t = 0, rh = 0, hz = 0;
    int16_t raw = 0;
    uint16_t ts = 0, hs = 0;
    HTS221Calibration cal = {};
    printf("who am i 0x%02X\r\n", (unsigned)(sensor.who_am_i()));   // Read WHO_AM_I, () → uint8_t (0xBC)
                                                                    // identifies the chip on the bus
    ok = sensor.read(t, rh);                                        // Read temperature and humidity, (temperature_c, humidity_pct) → bool
                                                                    // one-shot conversion, converted with the calibration
    printf("temperature %.2f C humidity %.1f %%rH\r\n", (double)(t), (double)(rh));
    printf("humidity %.1f\r\n", (double)(sensor.read_humidity()));  // Read humidity only, () → float %rH
                                                                    // one-shot conversion, clamped to 0–100 (NAN on error)
    printf("temperature %.2f\r\n", (double)(sensor.read_temperature()));// Read temperature only, () → float °C
                                                                    // one-shot conversion (NAN on error)
    ok = sensor.read_raw_humidity(raw);                             // Read raw humidity, (raw) → bool
                                                                    // signed 16-bit ADC output
    printf("raw humidity %ld\r\n", (long)(raw));
    ok = sensor.read_raw_temperature(raw);                          // Read raw temperature, (raw) → bool
                                                                    // signed 16-bit ADC output
    printf("raw temperature %ld\r\n", (long)(raw));
    sensor.read_calibration(cal);                                   // Read calibration, (cal) → void
                                                                    // decoded two-point coefficients
    printf("H0_rH %.1f H1_rH %.1f T0 %.2f C T1 %.2f C\r\n", (double)(cal.h0_rh), (double)(cal.h1_rh), (double)(cal.t0_degc), (double)(cal.t1_degc));
    printf("dew point %.2f C\r\n", (double)(sensor.dew_point()));   // Compute dew point, () → float °C
                                                                    // Magnus formula over one measurement (NAN on error)
    printf("status 0x%02X\r\n", (unsigned)(sensor.status()));       // Read STATUS_REG, () → uint8_t
                                                                    // STATUS_H_DA = 0x02, STATUS_T_DA = 0x01
    printf("data ready %lu\r\n", (unsigned long)(sensor.data_ready()));// Check data available, () → bool
                                                                    // both H_DA and T_DA set

    ok = sensor.set_output_data_rate(1);                            // Set output data rate, (hz 0|1|7|12.5) → bool
                                                                    // continuous sampling at 1 Hz
    ok = sensor.get_output_data_rate(hz);                           // Get output data rate, (hz) → bool
                                                                    // decodes CTRL_REG1.ODR
    printf("rate %.1f Hz\r\n", (double)(hz));
    HAL_Delay(1200);
    ok = sensor.read_continuous(t, rh);                             // Read latest sample, (temperature_c, humidity_pct) → bool
                                                                    // no conversion is started
    printf("continuous %.2f C %.1f %%rH\r\n", (double)(t), (double)(rh));
    ok = sensor.set_output_data_rate(0);                            // Set output data rate, (hz 0|1|7|12.5) → bool
                                                                    // back to one-shot mode

    ok = sensor.set_averaging(32, 64);                              // Set averaging, (temperature_samples 2–256, humidity_samples 4–512) → bool
                                                                    // more samples lower the noise and lengthen the conversion
    ok = sensor.get_averaging(ts, hs);                              // Get averaging, (temperature_samples, humidity_samples) → bool
                                                                    // decodes AV_CONF
    printf("averaging %lu %lu\r\n", (unsigned long)(ts), (unsigned long)(hs));
    ok = sensor.set_averaging(16, 32);
    ok = sensor.set_block_data_update(true);                        // Set block data update, (enabled) → bool
                                                                    // keeps LSB/MSB of one sample consistent

    ok = sensor.set_heater(true);                                   // Switch the heater, (on) → bool
                                                                    // drives off condensation; do not measure while on
    printf("heater on %lu\r\n", (unsigned long)(sensor.is_heater_on()));// Heater state, () → bool
                                                                    // reads CTRL_REG2.HEATER
    ok = sensor.set_heater(false);

    ok = sensor.configure_drdy(true, true, false);                  // Configure DRDY pin, (enabled, active_high=true, open_drain=false) → bool
                                                                    // routes data-ready to the DRDY pin
    printf("poll 0x%02X\r\n", (unsigned)(sensor.poll_interrupt())); // Poll interrupt status, () → uint8_t
                                                                    // STATUS_REG; flags clear when the data is read
    ok = sensor.configure_drdy(false);

    ok = sensor.power_down();                                       // Power down, () → bool
                                                                    // clears CTRL_REG1.PD
    ok = sensor.power_up();                                         // Power up, () → bool
                                                                    // sets CTRL_REG1.PD
    ok = sensor.on_interrupt(on_drdy);                              // Subscribe to data-ready, (callback, intPin=nullptr) → bool
                                                                    // needs a DRDY pin on the connection
    sensor.off_interrupt();                                         // Unsubscribe, () → void
                                                                    // disables the DRDY output
    ok = sensor.reboot();                                           // Reload trimming, () → bool
                                                                    // sets BOOT, waits, re-reads the calibration
    printf("done ok=%lu\r\n", (unsigned long)(ok));
    while (true) { HAL_Delay(1000); }
}
