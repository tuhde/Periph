#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "AXP2101.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x34);
    AXP2101Full chip(connection);                       // Create AXP2101 Full driver, (connection)

    uint8_t onSrc = chip.powerOnSource();               // Power on source, () → uint8_t
                                                        // PWRON_STATUS flags: key, IRQ pin, VBUS, battery
    printf("power on source: 0x%02X\r\n", (unsigned)onSrc);
    uint8_t offSrc = chip.powerOffSource();             // Power off source, () → uint8_t
                                                        // PWROFF_STATUS flags: why the PMIC last powered off
    printf("power off source: 0x%02X\r\n", (unsigned)offSrc);
    float vbus = chip.vbusVoltage();                    // Vbus voltage, () → float V
                                                        // VBUS ADC channel, 1 mV per LSB
    printf("vbus voltage: %.3f\r\n", (double)vbus);
    float vsys = chip.systemVoltage();                  // System voltage, () → float V
                                                        // VSYS ADC channel, 1 mV per LSB
    printf("system voltage: %.3f\r\n", (double)vsys);
    float ts = chip.tsVoltage();                        // Ts voltage, () → float V
                                                        // TS pin ADC channel, 0.5 mV per LSB
    printf("ts voltage: %.3f\r\n", (double)ts);
    float tdie = chip.dieTemperature();                 // Die temperature, () → float °C
                                                        // die-temperature channel, vendor formula (verify on hardware)
    printf("die temperature: %.1f\r\n", (double)tdie);
    AXP2101Readings snapshot;
    chip.readAll(snapshot);                             // Read all, () → void (fills a struct)
                                                        // one call returns every measurement plus charge state and VBUS flag
    printf("read all: %.3f V\r\n", (double)snapshot.batteryVoltage);
    AXP2101Status flags;
    chip.status(flags);                                 // Status, () → void (fills a struct)
                                                        // decodes STATUS1 / STATUS2 into named flags
    printf("status: %s\r\n", flags.batteryDirection);
    chip.setAdcChannels(true, true, true, true, true, false); // Set adc channels, (vbat=True, ts=True, vbus=True, vsys=True, tdie=True, gpadc=False) → bool
                                                        // ADC_ENABLE channel mask; GPADC stays off
    chip.setAdcLowFrequency(false);                     // Set adc low frequency, (enabled) → bool
                                                        // TS_H bit 7: slow 64 s sampling only when powered off on battery
    chip.setCharging(true);                             // Set charging, (enabled) → bool
                                                        // MODULE_ENABLE bit 1: main-cell charging
    chip.setChargeVoltage(4.2f);                        // Set charge voltage, (volts 4.0/4.1/4.2/4.35/4.4) → bool
                                                        // CV register; 4.35 / 4.4 V are for high-voltage cells only
    chip.setChargeCurrent(0.5f);                        // Set charge current, (amps 0–1.5) → bool
                                                        // ICC: 25 mA steps up to 0.2 A, 100 mA steps above; capped by the charge voltage
    chip.setPrechargeCurrent(0.125f);                   // Set precharge current, (amps 0–0.2) → bool
                                                        // IPRECHG in 25 mA steps
    chip.setTermination(0.125f, true);                  // Set termination, (amps 0–0.2, enabled=True) → bool
                                                        // ITERM current in 25 mA steps plus the enable bit
    chip.setInputCurrentLimit(1.5f);                    // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → bool
                                                        // IINLIM: input current limit
    chip.setInputVoltageLimit(4.36f);                   // Set input voltage limit, (volts 3.88–5.08) → bool
                                                        // VINDPM in 80 mV steps
    chip.setMinSystemVoltage(3.7f);                     // Set min system voltage, (volts 3.2–3.9) → bool
                                                        // MIN_VSYS in 100 mV steps
    chip.setChargeTimers(60, 12, true, true, true);     // Set charge timers, (precharge_min=60, done_hours=12, precharge_enabled=True, done_enabled=True, slow_in_dpm=True) → bool
                                                        // CHG_TIMER: pre-charge 40–70 min, charge-done 5–20 h
    chip.setThermalRegulation(100);                     // Set thermal regulation, (celsius 60/80/100/120) → bool
                                                        // THERMAL_REG: charger thermal-regulation threshold
    chip.setDieProtection(125, true);                   // Set die protection, (level1_celsius=125, detect=True) → bool
                                                        // DIE_TEMP_CTRL: die over-temperature level 1 and detection
    chip.setBatteryDetection(true);                     // Set battery detection, (enabled) → bool
                                                        // BAT_DETECT bit 0
    chip.setBackupCharging(false, 2.9f);                // Set backup charging, (enabled, volts=2.9 V) → bool
                                                        // MODULE_ENABLE bit 2 and BTN_BAT_VTERM for the coin cell
    chip.setChgled("type_a", "hiz");                    // Set chgled, (mode='type_a', output='hiz') → bool
                                                        // CHGLED_CTRL: charge indicator behaviour
    chip.configureTs(true, 50, "when_adc");             // Configure ts, (battery_sensor=True, current_ua=50 µA, source='when_adc') → bool
                                                        // TS_CTRL: thermistor mode, injected current and source mode
    chip.setTsLimits(1.312f, 0.176f, 1.984f, 0.152f);   // Set ts limits, (charge_cold, charge_hot, work_cold, work_hot) → bool V
                                                        // VLTF/VHTF thresholds as TS voltages (32 mV / 2 mV steps)
    chip.setTsHysteresis(0.032f, 0.004f);               // Set ts hysteresis, (low_to_normal=0.032 V, high_to_normal=0.004 V) → bool
                                                        // TS_HYS_L2H (16 mV steps) and TS_HYS_H2L (4 mV steps)
    chip.configureJeita(false, -1.0f, -1.0f, true, false, 0, 1); // Configure jeita, (enabled, cool_voltage=None, warm_voltage=None, cool_half_current=True, warm_half_current=False, cool_voltage_drop=0, warm_voltage_drop=1) → bool
                                                        // JEITA profile; thresholds are left unchanged when None
    bool railEnabled = false;
    float railVolts = 0.0f;
    chip.getRail("aldo1", railEnabled, railVolts);      // Get rail, (name) → bool, (enabled, volts V)
                                                        // enable bit and programmed voltage of one rail
    printf("rail aldo1: %s %.2f V\r\n", (railEnabled) ? "true" : "false", (double)railVolts);
    chip.setRailVoltage("aldo1", railVolts);            // Set rail voltage, (name, volts) → bool V
                                                        // rounds to the nearest step inside the rail range; raises outside it
    chip.setRailEnabled("aldo1", railEnabled);          // Set rail enabled, (name, enabled) → bool
                                                        // enable bit in DCDC_EN / LDO_EN0 / LDO_EN1
    chip.configureDcdc("dcdc1", false, -1);             // Configure dcdc, (name, always_pwm=False, dvm=None) → bool
                                                        // forced-PWM bit per DCDC; DVM only for DCDC2 / DCDC3
    chip.configureDcdcGlobal(false, false, false, false, 60); // Configure dcdc global, (force_ccm=False, slow_ramp=False, frequency_spread=False, spread_100khz=False, uvp_debounce_us=60 µs) → bool
                                                        // DCDC_EN bits 6 / 5 and DCDC_PWM bits 7:6, 1:0
    chip.setOffDischarge(true);                         // Set off discharge, (enabled) → bool
                                                        // COMMON_CONFIG bit 5: discharge outputs when off
    chip.configureSleep(false, true, false);            // Configure sleep, (wake_on_irq=False, pwrok_low=True, restore_voltages=False) → bool
                                                        // SLEEP_CTRL bits 4, 3, 2
    chip.configurePowerKey(512, 6.0f, 1.5f, true, false, false); // Configure power key, (on_ms=512, off_s=6, irq_s=1.5, long_press_off=True, restart_instead=False, force_off_16s=False) → bool
                                                        // power-key timing and the actions of a long press
    chip.configurePowerOff(true, 2.6f, true, 0x0F);     // Configure power off, (die_over_temp=True, off_voltage=2.6 V, dcdc_ovp=True, dcdc_uvp=0x0F) → bool
                                                        // power-off sources: die over-temperature, VOFF threshold, DCDC OVP / UVP
    chip.configurePwrok(true, 8, true, false, false);   // Configure pwrok, (check=True, delay_ms=8 ms, off_delay=True, reverse_off_sequence=False, restart_on_low=False) → bool
                                                        // PWROK_CTRL delay and sequencing
    chip.setBatfetOffEnable(false);                     // Set batfet off enable, (enabled) → bool
                                                        // BATFET_CTRL bit 3: BATFET on when powered off on battery only
    chip.configureFastPoweron(false, false);            // Configure fast poweron, (poweron, wakeup, sequence=None) → bool
                                                        // FAST_PWRON enables; the per-rail start sequence is left untouched
    chip.configureWatchdog(false, 64, "irq");           // Configure watchdog, (enabled, timeout_s=64 s, action='irq') → bool
                                                        // watchdog disabled here; timeout and action are still programmed
    chip.feedWatchdog();                                // Feed watchdog, () → bool
                                                        // WATCHDOG_CTRL bit 3: clears the watchdog counter
    chip.configureBatteryWarning(10, 1);                // Configure battery warning, (level2=10 %, level1=1 %) → bool
                                                        // LOWBAT_WARN: warning levels 2 (5–20 %) and 1 (0–15 %)
    chip.setGaugeEnabled(true);                         // Set gauge enabled, (enabled) → bool
                                                        // MODULE_ENABLE bit 3: fuel gauge
    chip.resetGauge(false);                             // Reset gauge, (full=False) → bool
                                                        // GAUGE_RESET bit 3 (bit 2 for the deeper reset)
    chip.setGpio1(false);                               // Set gpio1, (low) → bool
                                                        // GPIO1 open-drain output: False = Hi-Z
    chip.writeDataBuffer(0, 0x5A);                      // Write data buffer, (index 0–3, value 0–255) → bool
                                                        // stores a byte that survives soft restarts
    uint8_t stored = 0;
    chip.readDataBuffer(0, stored);                     // Read data buffer, (index 0–3) → uint8_t
                                                        // reads the byte back
    printf("data buffer 0: %d\r\n", (int)stored);
    chip.enableInterrupt(AXP2101Full::SOURCE_VBUS_INSERT); // Enable interrupt, (source) → bool
                                                        // sets the source bits in IRQ_EN0..2
    chip.disableInterrupt(AXP2101Full::SOURCE_VBUS_INSERT); // Disable interrupt, (source) → bool
                                                        // clears the source bits in IRQ_EN0..2
    uint32_t irq = chip.pollInterrupt();                // Poll interrupt, () → uint8_t
                                                        // reads IRQ_STATUS0..2, clears the bits seen and returns the 24-bit status
    printf("interrupt status: %lu\r\n", (unsigned long)irq);

    // Destructive commands stay disabled; set kEnableDangerous only on a bench supply.
    const bool kEnableDangerous = false;
    if (kEnableDangerous) {
        chip.powerOff();                                // Power off, () → bool
                                                        // COMMON_CONFIG bit 0: soft power-off, cuts every output except RTCLDO
        chip.restart();                                 // Restart, () → bool
                                                        // COMMON_CONFIG bit 1: restarts the system
        chip.sleep();                                   // Sleep, () → bool
                                                        // SLEEP_CTRL bit 0: enter sleep
        chip.wakeup();                                  // Wakeup, () → bool
                                                        // SLEEP_CTRL bit 1: wake up
    }

    while (true) HAL_Delay(1000);
}
