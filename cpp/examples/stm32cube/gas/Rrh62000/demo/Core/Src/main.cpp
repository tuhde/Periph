#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Rrh62000.h"

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

#define P(...) printf(__VA_ARGS__)
#define DELAY(ms) HAL_Delay(ms)

// Formats v with `dec` decimals using integers only (no %f on AVR / printk).
static const char* fix(char* buf, size_t n, float v, int dec) {
    long scale = dec == 2 ? 100 : 10;
    long x = (long)(v * scale + (v < 0 ? -0.5f : 0.5f));
    const char* sign = x < 0 ? "-" : "";
    if (x < 0) x = -x;
    snprintf(buf, n, dec == 2 ? "%s%ld.%02ld" : "%s%ld.%01ld", sign, x / scale, x % scale);
    return buf;
}

static void print_measurement(const RRH62000Measurement& m) {
    char a[12], b[12], c[12], t[12], h[12], q[12];
    P("PM1 %s  PM2.5 %s  PM10 %s ug/m3  %s C  %s %%RH  TVOC %lu ug/m3  eCO2 %u ppm  IAQ %s\r\n",
      fix(a, 12, m.pm1_ug_m3, 1), fix(b, 12, m.pm2_5_ug_m3, 1), fix(c, 12, m.pm10_ug_m3, 1),
      fix(t, 12, m.temperature_c, 2), fix(h, 12, m.humidity_rh, 2), (unsigned long)m.tvoc,
      (unsigned)m.eco2_ppm, fix(q, 12, m.iaq, 2));
}

static const char* iaq_label(float iaq) {
    if (iaq < 2.0f) return "very good";
    if (iaq < 3.0f) return "good";
    if (iaq < 4.0f) return "medium";
    if (iaq < 5.0f) return "poor";
    return "bad";
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x69);
    RRH62000Full sensor(connection);                               // Create RRH62000 driver, (connection, accessIntervalMs=1000) → void
    sensor.init();                                                 // Wait for start-up, check firmware version, () → bool

    // --- Identity and settings ---
    // The module may only be accessed once per second; every call below waits
    // for that interval, so this section takes several seconds.
    uint8_t major = 0, minor = 0, patch = 0;
    sensor.firmware_version(major, minor);                         // Firmware version, (major&, minor&) → bool
    P("firmware %u.%u\r\n", major, minor);
    sensor.algorithm_version(major, minor, patch);                 // Algorithm version, (major&, minor&, patch&) → bool
    P("algorithm %u.%u.%u\r\n", major, minor, patch);
    uint8_t uid[6];
    sensor.unique_id(uid);                                         // Unique ID, (uid[6]) → bool
    P("unique ID %02X%02X%02X%02X%02X%02X\r\n", uid[0], uid[1], uid[2], uid[3], uid[4], uid[5]);
    P("moving average %d, fan %d %%, ", sensor.get_moving_average(), sensor.get_fan_speed());  // Moving average, () → int / Fan speed, () → int
    P("cleaning every %ld s for %d s\r\n", (long)sensor.get_cleaning_interval(), sensor.get_cleaning_time());  // Cleaning interval, () → int32_t / Cleaning time, () → int

    // --- Room air-quality station ---
    // A complete sample arrives every 3 s. The IAQ index follows the German UBA
    // scale; PM2.5 uses the KCl reference. Allow up to 48 h of conditioning on
    // first use before trusting TVOC and eCO2.
    RRH62000Data d;
    char a[12], b[12], c[12], e[12], f[12];
    for (int i = 0; i < 10; i++) {
        if (!sensor.read_all(d)) {                                 // Complete block, (data&) → bool
            P("read failed (error %d)\r\n", sensor.last_error());   // Reason of the last failure, () → int
            continue;
        }
        P("IAQ %s (%s)  PM2.5 %s ug/m3  eCO2 %u ppm  TVOC %lu ug/m3  %s C  %s %%RH\r\n",
          fix(a, 12, d.iaq, 2), iaq_label(d.iaq), fix(b, 12, d.particles.pm2_5_ug_m3, 1), (unsigned)d.eco2_ppm,
          (unsigned long)d.tvoc, fix(c, 12, d.temperature_c, 1), fix(e, 12, d.humidity_rh, 1));
    }

    // --- Particle counts and status ---
    // Number concentrations count particles from the bin size up to 10 µm per cm³.
    RRH62000Particles p;
    if (sensor.read_particles(p)) {                                // Particle values, (particles&) → bool
        P("particles/cm3 >0.3um %s >0.5um %s >1um %s ", fix(a, 12, p.nc_0_3, 1), fix(b, 12, p.nc_0_5, 1), fix(c, 12, p.nc_1, 1));
        P(">2.5um %s >4um %s\r\n", fix(e, 12, p.nc_2_5, 1), fix(f, 12, p.nc_4, 1));
        P("PM2.5 KCl %s / smoke %s ug/m3\r\n", fix(a, 12, p.pm2_5_ug_m3, 1), fix(b, 12, p.pm2_5_smoke_ug_m3, 1));
    }
    RRH62000Status st;
    if (sensor.read_status(st))                                    // Status flags, (status&) → bool
        P("status: high %d dust %d fan speed %d fan %d\r\n", st.high_concentration, st.dust_accumulation,
          st.fan_speed, st.fan);
    P("new data pending: %d\r\n", sensor.data_ready());             // New sample available, () → int

    // --- Quieter, smoother operation ---
    // 30 moving averages smooth the PM values (slower response); 70 % fan speed
    // is quieter.
    sensor.set_moving_average(30);                                 // Moving average, (count 1–60) → bool
    sensor.set_fan_speed(70);                                      // Fan speed, (percent 60–100) → bool
    P("moving average %d, fan %d %%\r\n", sensor.get_moving_average(), sensor.get_fan_speed());

    // --- Fan cleaning ---
    // A manual cleaning blows dust out of the air channel; the automatic
    // cleaning is shortened to every 3 days for 10 s.
    sensor.start_cleaning();                                       // Start fan cleaning, () → bool
    for (int i = 0; i < 30; i++) {
        if (sensor.cleaning_done() == 1) {                         // Cleaning status, () → int
            P("cleaning done\r\n");
            break;
        }
    }
    sensor.set_cleaning_interval(3UL * 24 * 3600);                 // Cleaning interval, (seconds) → bool
    sensor.set_cleaning_time(10);                                  // Cleaning time, (seconds) → bool
    P("cleaning every %ld s\r\n", (long)sensor.get_cleaning_interval());

    // --- Sleep, diagnostics, reset ---
    // Sleep stops the fan; after wake-up the first valid sample takes 3.3 s.
    sensor.sleep();                                                // Sleep, () → bool
    DELAY(5000);
    sensor.wake();                                                 // Wake up, () → bool
    uint32_t ohms = 0;
    sensor.read_mox_resistance(ohms);                              // MOX resistance, (ohms&) → bool
    P("MOX resistance %lu ohm\r\n", (unsigned long)ohms);
    sensor.soft_reset();                                           // Reset, () → bool
    RRH62000Measurement m;
    if (sensor.read(m)) print_measurement(m);                      // Main values, (measurement&) → bool
    while (1) {}
}
