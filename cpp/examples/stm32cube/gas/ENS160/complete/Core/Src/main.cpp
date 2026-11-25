#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ENS160.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x53);
    ENS160Full sensor(connection);

    static int passed = 0, failed = 0;

    HAL_Delay(2000);

    uint8_t major, minor, release;
    sensor.get_firmware_version(major, minor, release);  // Get firmware version, (major&, minor&, release&) → void
                                                          // switches to IDLE, issues GET_APPVER, returns to STANDARD
    printf("Firmware: ");
    printf("%d", major);
    printf(".");
    printf("%d", minor);
    printf(".");
    printf("%d\r\n", release);

    sensor.set_compensation(25.0f, 50.0f);               // Set compensation, (temp_celsius, rh_percent) → void
                                                          // improves accuracy with external T/RH readings

    sensor.configure_interrupt(true, false, false, true, false);  // Configure interrupt, (enabled, active_high, push_pull, on_data, on_gpr) → void
                                                          // sets INTn pin behavior for new data notification

    printf("Waiting for warm-up...\r\n");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            HAL_Delay(1000);
        }
    }

    float tvoc = sensor.read_tvoc();                     // Read TVOC, () → float ppb
    float eco2 = sensor.read_eco2();                     // Read eCO2, () → float ppm
    uint8_t aqi = sensor.read_aqi();                     // Read AQI, () → uint8_t 1–5
    float ethanol = sensor.read_ethanol();               // Read ethanol, () → float ppb
                                                          // alias of DATA_TVOC at 0x22
    float r1 = sensor.read_raw_resistance(1);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float r4 = sensor.read_raw_resistance(4);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float temp_actual, rh_actual;
    sensor.read_compensation_actuals(temp_actual, rh_actual);  // Read compensation actuals, (temp_celsius&, rh_percent&) → void
                                                          // returns T/RH values used by sensor

    printf("TVOC=");
    printf("%.0f", tvoc);
    printf(" ppb, eCO2=");
    printf("%.0f", eco2);
    printf(" ppm, AQI=");
    printf("%d\r\n", aqi);
    printf("Ethanol=");
    printf("%.0f", ethanol);
    printf(" ppb, R1=");
    printf("%.0f", r1);
    printf(" Ohm, R4=");
    printf("%.0f", r4);
    printf(" Ohm\r\n");
    printf("Actual T=");
    printf("%.1f", temp_actual);
    printf(" C, RH=");
    printf("%.1f", rh_actual);
    printf(" %\r\n");

    sensor.sleep();                                      // Enter deep sleep, () → void
                                                          // reduces current to ~10 uA
    HAL_Delay(1000);
    sensor.wake();                                       // Wake and resume sensing, () → void
                                                          // transitions IDLE then STANDARD

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
