#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "VL53L1X.h"

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

// Long-range doorway people counter with a split ROI: the sensor hangs
// overhead (up to 2.5 m); two 8x16 ROIs (SPADs 167 and 231, the half-array
// centres used by ST's people-counting code) form two virtual beams, and the
// order in which they are blocked tells IN from OUT.

static const uint8_t ZONE_CENTRES[2] = { 167, 231 };   // left, right
static const uint16_t OCCUPIED_MM = 300;
static const int MAX_EVENTS = 50;
static const uint32_t MAX_MS = 60000;
static const uint32_t SNAPSHOT_MS = 30000;
static const float BRIGHT_MCPS = 5.0f;

static uint32_t elapsedMs = 0;

static bool measure(VL53L1XFull& sensor, int zone, uint16_t& mm) {
    sensor.setRoiCenter(ZONE_CENTRES[zone]);                // Set ROI centre, (spad) → void
    mm = sensor.distance();                                 // Measure distance, () → uint16_t mm
    elapsedMs += 33;
    return sensor.rangeValid();                             // Check last measurement, () → bool
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, VL53L1XMinimal::I2C_ADDRESS);
    VL53L1XFull sensor(connection);                         // Create VL53L1X Full driver, (connection)

    // --- Two virtual beams ---
    // Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps
    // the two zones fast enough to catch a walking person. An 8x16 ROI covers
    // one half of the SPAD array, so alternating the centre alternates beams.
    sensor.setDistanceMode(VL53L1XFull::DistanceMode::Long);  // Set distance mode, (mode) → bool
    sensor.setTimingBudget(33000);                          // Set timing budget, (budgetUs µs) → bool
    sensor.setRoi(8, 16);                                   // Set ROI size, (width SPADs, height SPADs) → bool
    printf("optical centre SPAD %u, zone centres %u/%u\r\n",
           (unsigned)sensor.opticalCenter(), (unsigned)ZONE_CENTRES[0], (unsigned)ZONE_CENTRES[1]);  // Read optical-centre SPAD, () → uint8_t

    // --- Learn the empty doorway ---
    // Twenty readings per zone give the floor distance each beam sees when
    // nobody is there; invalid readings are ignored.
    float baseline[2];
    for (int zone = 0; zone < 2; zone++) {
        uint32_t sum = 0;
        int n = 0;
        for (int i = 0; i < 20; i++) {
            uint16_t mm;
            if (measure(sensor, zone, mm)) { sum += mm; n++; }
        }
        baseline[zone] = n ? (float)sum / n : 4000.0f;
    }
    printf("baseline left %.0f mm, right %.0f mm\r\n", (double)baseline[0], (double)baseline[1]);

    // --- Count crossings ---
    // A person entering blocks the left beam first, then the right one (and
    // the reverse when leaving). Once both beams clear, the recorded order
    // decides the direction.
    int countIn = 0, countOut = 0, events = 0;
    int sequence[2];
    int seqLen = 0;
    uint32_t lastSnapshot = 0;
    while (events < MAX_EVENTS && elapsedMs < MAX_MS) {
        uint16_t r[2];
        bool occupied[2];
        for (int zone = 0; zone < 2; zone++) {
            bool valid = measure(sensor, zone, r[zone]);
            occupied[zone] = valid && r[zone] < baseline[zone] - OCCUPIED_MM;
            if (occupied[zone] && seqLen < 2 && (seqLen == 0 || sequence[0] != zone)) {
                sequence[seqLen++] = zone;
            }
        }
        if (!occupied[0] && !occupied[1] && seqLen > 0) {
            if (seqLen == 2 && sequence[0] == 0) countIn++;
            else if (seqLen == 2 && sequence[0] == 1) countOut++;
            events++;
            printf("IN %d OUT %d (left %u, right %u)\r\n", countIn, countOut, (unsigned)r[0], (unsigned)r[1]);
            seqLen = 0;
        }

        // --- Watch the light ---
        // Sunlight through an open door raises the ambient rate and eats
        // long-mode range; short mode keeps working up to ~1.3 m.
        if (elapsedMs - lastSnapshot >= SNAPSHOT_MS) {
            lastSnapshot = elapsedMs;
            sensor.distance();                              // Measure distance, () → uint16_t mm
            VL53L1XFull::Measurement m = sensor.readMeasurement();  // Read result block, () → Measurement
            printf("signal %.2f MCPS, ambient %.2f MCPS\r\n", (double)m.signalRateMcps, (double)m.ambientRateMcps);
            if (m.ambientRateMcps > BRIGHT_MCPS &&
                sensor.distanceMode() == VL53L1XFull::DistanceMode::Long) {  // Read distance mode, () → DistanceMode
                sensor.setDistanceMode(VL53L1XFull::DistanceMode::Short);  // Set distance mode, (mode) → bool
                printf("bright ambient light: switched to short distance mode\r\n");
            }
        }
    }

    // --- Restore the full field of view ---
    sensor.setRoi(16, 16);                                  // Set ROI size, (width SPADs, height SPADs) → bool
    printf("done: IN %d OUT %d\r\n", countIn, countOut);
    while (true) HAL_Delay(1000);
}
