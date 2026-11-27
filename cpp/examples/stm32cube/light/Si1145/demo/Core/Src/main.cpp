#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Si1145.h"

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

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

static volatile uint8_t g_events = 0;
static volatile uint8_t g_last = 0;
static void on_event(uint8_t status) { g_events = (uint8_t)(g_events + 1); g_last = status; }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x60);
    Si1145Full sensor(connection);                                 // Create Si1145 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool

    // --- Identity and calibration ---
    // PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the
    // factory calibration bytes.
    uint8_t part = 0, rev = 0, seq = 0;
    sensor.read_id(part, rev, seq);                                // Identity registers, (part_id&, rev_id&, seq_id&) → bool
    printf("PART_ID 0x%02X REV_ID 0x%02X SEQ_ID 0x%02X\r\n", (unsigned)part, (unsigned)rev, (unsigned)seq);
    uint8_t cal[12];
    sensor.read_calibration(cal);                                  // GET_CAL, (cal[12]) → bool
    printf("calibration");
    for (int i = 0; i < 12; i++) printf(" %02X", (unsigned)cal[i]);
    printf("\r\n");
    uint8_t counter = 0, err = 0;
    sensor.read_response(counter, err);                            // RESPONSE, (counter&, error&) → bool
    printf("counter %u error 0x%02X\r\n", (unsigned)counter, (unsigned)err);

    // --- Proximity, UV index and light for 10 s ---
    // Proximity counts rise as a hand approaches the LED/sensor window.
    Si1145Reading r;
    for (int i = 0; i < 10; i++) {
        sensor.read(r);                                            // Forced PS + ALS, (reading&) → bool
        printf("%2d s prox %5u UV %u.%02u vis %5u ir %5u\r\n", i, (unsigned)r.proximity,
              uv_whole(r.uv_index), uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared);
        HAL_Delay(1000);
    }

    // --- LED current and photodiode ---
    // Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
    // photodiode a fraction of the large one.
    printf("Hold a hand above the sensor\r\n");
    HAL_Delay(3000);
    const uint8_t levels[4] = {1, 4, 8, 15};
    uint16_t v = 0;
    for (int i = 0; i < 4; i++) {
        sensor.set_led_current(1, levels[i]);                      // LED drive, (led, level) → bool
        sensor.read_proximity(v);                                  // Forced PS, (value&, channel=1) → bool
        printf("LED level %2u -> %u\r\n", (unsigned)levels[i], (unsigned)v);
    }
    sensor.set_led_current(1, 3);
    for (int large = 0; large < 2; large++) {
        sensor.set_ps_photodiode(1, large != 0);                   // PS photodiode, (channel, large) → bool
        sensor.read_proximity(v);
        printf("%s photodiode -> %u\r\n", large ? "large" : "small", (unsigned)v);
    }
    sensor.set_ps_led(1, 0x01);                                    // PS LED select, (channel, leds) → bool

    // --- Gain and high range ---
    // Each gain step doubles the integration time; the high range divides the sensitivity by
    // 14.5 for direct sunlight.
    uint16_t ir = 0;
    for (uint8_t gain = 0; gain <= 4; gain += 2) {
        sensor.set_gain(gain, gain, 0);                            // ADC gains, (visible, infrared, proximity) → bool
        sensor.read_visible(v);                                    // Forced ALS, (value&) → bool
        sensor.read_infrared(ir);                                  // Forced ALS, (value&) → bool
        printf("gain %u vis %u ir %u\r\n", (unsigned)gain, (unsigned)v, (unsigned)ir);
    }
    sensor.set_gain();
    sensor.set_high_range(true, true, false);                      // Signal range, (visible, infrared, proximity) → bool
    float uv = 0.0f;
    sensor.read_visible(v);
    sensor.read_uv_index(uv);                                      // Forced ALS, (value&) → bool
    printf("high range vis %u UV %u.%02u\r\n", (unsigned)v, uv_whole(uv), uv_frac(uv));
    sensor.set_high_range(false, false, false);
    sensor.set_alignment();                                        // Result alignment, (...) → bool
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    sensor.set_uv_coefficients(ucoef);                             // UCOEF0..3, (ucoef[4]) → bool

    // --- Autonomous mode with interrupt events and a proximity switch ---
    // PS + ALS every 100 ms; each finished PS1 measurement raises INT (when wired).
    const uint16_t threshold = 2000;
    sensor.set_channels(true, true, true, true);                   // CHLIST, (uv, visible, infrared, ps1, ...) → bool
    sensor.enable_interrupt(Si1145Full::SOURCE_PS1);               // INT_CFG + IRQ_ENABLE, (source) → bool
    sensor.on_interrupt(on_event);                                 // Subscribe, (callback, intPin=nullptr) → void
    sensor.start_autonomous(0.1f);                                 // Autonomous mode, (interval_s, proximity, als) → bool
    bool near = false;
    Si1145Raw raw;
    for (int i = 0; i < 50; i++) {
        sensor.read_raw(raw);                                      // Data registers, (raw&) → bool
        if ((raw.ps1 > threshold) != near) {
            near = raw.ps1 > threshold;
            printf("output %s (prox %u)\r\n", near ? "ON" : "OFF", (unsigned)raw.ps1);
        }
        HAL_Delay(100);
    }
    sensor.pause();                                                // PSALS_PAUSE, () → bool
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    printf("%u interrupt events, last 0x%02X\r\n", (unsigned)g_events, (unsigned)g_last);
    sensor.disable_interrupt(Si1145Full::SOURCE_PS1);              // IRQ_ENABLE clear, (source) → bool
    sensor.poll_interrupt();                                       // IRQ_STATUS, () → uint8_t sources

    // --- Auxiliary channels ---
    uint16_t vdd = 0;
    sensor.read_temperature_raw(v);                                // AUX temperature, (value&) → bool
    sensor.read_vdd_raw(vdd);                                      // AUX VDD, (value&) → bool
    printf("temperature raw %u VDD raw %u\r\n", (unsigned)v, (unsigned)vdd);
    sensor.force(false, true);                                     // ALS_FORCE without waiting, (proximity, als) → bool
    HAL_Delay(50);
    sensor.read_raw(raw);
    printf("raw vis %u ir %u aux %u\r\n", (unsigned)raw.visible, (unsigned)raw.infrared, (unsigned)raw.aux);

    // --- Address and reset ---
    sensor.set_i2c_address(0x60);                                  // I2C_ADDR + BUSADDR, (address) → bool
    sensor.reset();                                                // RESET + HW_KEY + defaults, () → bool
    sensor.read(r);
    printf("after reset prox %u vis %u\r\n", (unsigned)r.proximity, (unsigned)r.visible);
    while (1) {}
}
