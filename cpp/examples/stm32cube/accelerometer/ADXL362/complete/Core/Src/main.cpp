#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "ADXL362.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11).
// SPI1 is on APB2 (100 MHz), so the baud rate is 100 MHz / prescaler.
// CS is a plain GPIO on PB6 (D10), idle high; SPIConnectionSTM32Cube drives it
// but does not configure it, so it is set up here.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_6 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    gpioInit.Pin   = GPIO_PIN_6;
    gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
    gpioInit.Pull  = GPIO_NOPULL;
    gpioInit.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_WritePin(GPIOB, GPIO_PIN_6, GPIO_PIN_SET);
    HAL_GPIO_Init(GPIOB, &gpioInit);

    hspi1.Instance               = SPI1;
    hspi1.Init.Mode              = SPI_MODE_MASTER;
    hspi1.Init.Direction         = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize          = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity       = polarity;
    hspi1.Init.CLKPhase          = phase;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = prescaler;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_16, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);                         // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_6) → SPIConnectionSTM32Cube
    ADXL362Full accel(connection);                                           // Create ADXL362 Full driver, (connection) → ADXL362Full

    uint8_t devad, devmst, partid, revid;
    accel.device_id(devad, devmst, partid, revid);                           // Read device IDs, (devid_ad, devid_mst, partid, revid) → 4× byte
    printf("DEVID_AD=0x%02X DEVID_MST=0x%02X PARTID=0x%02X REVID=0x%02X\r\n",
           devad, devmst, partid, revid);

    accel.set_range(4);                                                      // Set measurement range, (range_g=4) → None
    accel.set_odr(200.0f);                                                   // Set output data rate, (odr_hz=200.0) → None
    accel.set_half_bandwidth(true);                                          // Set antialiasing bandwidth, (enabled=true) → None
    accel.set_noise_mode(ADXL362Full::NOISE_LOW);                            // Set noise mode, (mode=NOISE_LOW=1) → None

    float x, y, z;
    accel.read(x, y, z);                                                     // Read 12-bit acceleration, (x, y, z) → g, g, g
    printf("12-bit: x=%+.3f  y=%+.3f  z=%+.3f\r\n", x, y, z);

    accel.read_8bit(x, y, z);                                                // Read 8-bit acceleration, (x, y, z) → g, g, g
    printf(" 8-bit: x=%+.3f  y=%+.3f  z=%+.3f\r\n", x, y, z);

    float t = accel.temperature();                                           // Read temperature, () → float °C
    printf("temperature: %.2f C\r\n", t);

    uint8_t raw_status = accel.status();                                     // Read STATUS register, () → byte
    printf("status: 0x%02X\r\n", raw_status);
    printf("awake: %d\r\n", accel.awake() ? 1 : 0);                            // Check AWAKE bit, () → bool
    printf("data_ready: %d\r\n", accel.data_ready() ? 1 : 0);                  // Check DATA_READY, () → bool
    printf("fifo_entries: %u\r\n", accel.fifo_entries());                      // Read FIFO entry count, () → uint16_t

    accel.configure_fifo(ADXL362Full::FIFO_STREAM, false, 128);              // Configure FIFO, (mode=STREAM=2, store_temp=false, watermark=128) → None
    accel.set_activity_threshold(0.5f, true);                                // Set activity threshold, (threshold_g=0.5, referenced=true) → None
    accel.set_activity_time(5);                                              // Set activity time, (samples=5) → None
    accel.set_inactivity_threshold(0.2f, true);                              // Set inactivity threshold, (threshold_g=0.2, referenced=true) → None
    accel.set_inactivity_time(30);                                           // Set inactivity time, (samples=30) → None
    accel.enable_activity_detection(true);                                   // Enable activity detection, (enabled=true) → None
    accel.enable_inactivity_detection(true);                                 // Enable inactivity detection, (enabled=true) → None
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                    // Set link/loop mode, (mode=LOOP=3) → None

    accel.set_interrupt(1, ADXL362Full::SOURCE_DATA_READY, true);            // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → None
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);                 // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
    accel.set_interrupt_polarity(1, true);                                   // Set INT1 active-low, (pin=1, active_low=true) → None

    accel.self_test(true);                                                   // Enable self-test, (enabled=true) → None
    HAL_Delay(500);
    accel.self_test(false);                                                  // Disable self-test, (enabled=false) → None

    accel.soft_reset();                                                      // Soft-reset the chip, () → None
    while (true) HAL_Delay(1000);
}
