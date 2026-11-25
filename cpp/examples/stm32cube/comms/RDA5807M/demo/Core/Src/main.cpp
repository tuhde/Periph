#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "RDA5807M.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x10);
    RDA5807MFull fm(connection, /*frequency_mhz=*/100.0f, /*volume=*/5);

    // --- FM band scanner ---
    // Start at the bottom of the world-wide band and repeatedly seek upward
    // with SKMODE=1 (stop at band limit) so a seek that returns false means
    // the top of the band has been reached and the scan is done.
    fm.enable_rds(true);

    printf("Scanning...\r\n");
    while (true) {
        float freq;
        if (!fm.seek(true, freq)) break;
        if (!fm.is_station()) continue;

        uint8_t rssi = fm.signal_strength();
        bool stereo = fm.is_stereo();

        // --- Try to read the Program Service (station) name via RDS ---
        // Group types 0A/0B carry the 8-character PS name, four segments of
        // two characters each, addressed by block B bits 1:0. Give the
        // decoder up to 2 seconds to assemble a full name.
        char ps_name[9] = {0};
        bool have_all[4] = {false, false, false, false};
        unsigned long deadline = HAL_GetTick() + 2000;
        while (HAL_GetTick() < deadline) {
            if (fm.rds_ready()) {
                uint16_t a, b, c, d;
                if (fm.read_rds_group(a, b, c, d)) {
                    uint8_t group_type = b >> 12;
                    uint8_t is_b_variant = (b >> 11) & 1;
                    if (group_type == 0 && is_b_variant == 0) {
                        uint8_t seg = b & 0x03;
                        ps_name[seg * 2] = static_cast<char>(d >> 8);
                        ps_name[seg * 2 + 1] = static_cast<char>(d & 0xFF);
                        have_all[seg] = true;
                        if (have_all[0] && have_all[1] && have_all[2] && have_all[3]) break;
                    }
                }
            }
            HAL_Delay(40);
        }

        printf("%f", freq); printf(" MHz  RSSI=");
        printf("%d", rssi); printf("%d", stereo ? "  stereo  " : "  mono  ");
        printf("%d\r\n", (have_all[0] && have_all[1] && have_all[2] && have_all[3]) ? ps_name : "(no RDS name)");
    }
    printf("Scan complete.\r\n");
}
