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

static const char* const kRails[] = {"dcdc1", "dcdc2", "dcdc3", "dcdc4", "aldo1", "aldo2", "aldo3", "aldo4",
                                     "bldo1", "bldo2", "cpusldo", "dldo1", "dldo2"};
static const struct { uint32_t mask; const char* text; } kEvents[] = {
    {AXP2101Full::SOURCE_VBUS_INSERT, "VBUS inserted"},
    {AXP2101Full::SOURCE_VBUS_REMOVE, "VBUS removed"},
    {AXP2101Full::SOURCE_CHARGE_START, "charging started"},
    {AXP2101Full::SOURCE_CHARGE_DONE, "charging done"},
    {AXP2101Full::SOURCE_PWRON_SHORT, "power key short press"},
    {AXP2101Full::SOURCE_SOC_WARN_L2, "battery warning level 2"},
};

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, 0x34);
    AXP2101Full chip(connection);                       // Create AXP2101 Full driver, (connection)

    bool railEnabled = false;
    float railVolts = 0.0f;
    // --- Report why the board started and what every rail is doing ---
    // eFuse defaults differ per product, so the rails are read, never assumed.
    uint8_t src = chip.powerOnSource();                 // Read power-on flags, () → uint8_t
    printf("power-on source: 0x%02X\r\n", (unsigned)src);
    for (size_t i = 0; i < sizeof(kRails) / sizeof(kRails[0]); i++) {
        chip.getRail(kRails[i], railEnabled, railVolts); // Read rail state, (name, enabled, volts V) → bool
        printf("%s %s %.2f V\r\n", kRails[i], (railEnabled) ? "true" : "false", (double)railVolts);
    }

    // --- Program a conservative 4.2 V / 500 mA charge profile ---
    // 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
    // limit lets a USB charger feed both the system and the charger.
    chip.setChargeVoltage(4.2f);                        // Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → bool
    chip.setChargeCurrent(0.5f);                        // Set charge current, (amps 0–1.5) → bool
    chip.setInputCurrentLimit(1.5f);                    // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → bool
    chip.setTermination(0.05f, true);                   // Set termination current, (amps 0–0.2, enabled) → bool

    // --- Arm low-battery warnings and the power events of interest ---
    // The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
    chip.configureBatteryWarning(15, 5);                // Set warning levels, (level2 %, level1 %) → bool
    for (size_t i = 0; i < sizeof(kEvents) / sizeof(kEvents[0]); i++) {
        chip.enableInterrupt(kEvents[i].mask);          // Enable interrupt source, (source) → bool
    }
    chip.pollInterrupt();                               // Clear stale status, () → uint32_t

    // --- Monitor for 120 s ---
    // One status line per second; pollInterrupt() returns and clears every pending event.
    for (int n = 0; n < 120; n++) {
        AXP2101Readings d;
        chip.readAll(d);                                // Read all measurements, (out) → void
        AXP2101Status st;
        chip.status(st);                                // Read status flags, (out) → void
        printf("%.3f V %d %%  vbus %s %.2f V  sys %.2f V  die %.1f C  state %d  %s\r\n", (double)d.batteryVoltage, (int)d.batteryPercent, (d.vbusPresent) ? "true" : "false", (double)d.vbusVoltage, (double)d.systemVoltage, (double)d.dieTemperature, (int)d.chargeState, st.batteryDirection);
        uint32_t got = chip.pollInterrupt();            // Read and clear events, () → uint32_t
        for (size_t i = 0; i < sizeof(kEvents) / sizeof(kEvents[0]); i++) {
            if (got & kEvents[i].mask) {
                printf("event: %s\r\n", kEvents[i].text);
            }
        }
        HAL_Delay(1000);
    }

    while (true) HAL_Delay(1000);
}
