//go:build tinygo

// ICM20948 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the ICM20948Full API: configuration, temperature,
// raw and burst readings, offsets, the AK09916 magnetometer, auxiliary-bus
// access, FIFO, wake-on-motion, interrupts, self-test and reset.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil) // Create I2C connection, (i2c, addr=0x68) → (*I2CConnection)

	chip, err := imu.NewICM20948Full(conn) // Create ICM20948 driver, (connection) → (*ICM20948Full, error)
	must(err)

	ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32, float32, float32 m/s², error)
	// converts raw accel registers to m/s² (16384 LSB/g at ±2g)
	must(err)
	gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32, float32, float32 rad/s, error)
	// converts raw gyro registers to rad/s (131 LSB/(°/s) at ±250dps)
	must(err)
	accel, gyro, temp, err := chip.ReadAll() // Read one coherent sample, () → ([3]float32 m/s², [3]float32 rad/s, float32 °C, error)
	// burst-reads registers 0x2D…0x3A so all three belong to one sample
	must(err)
	t, err := chip.Temperature() // Read die temperature, () → (float32 °C, error)
	// converts raw temp register: raw/333.87 + 21.0
	must(err)
	rax, ray, raz, err := chip.AccelRaw() // Read raw accel values, () → (int16, int16, int16, error)
	// returns signed 16-bit accelerometer counts
	must(err)
	rgx, rgy, rgz, err := chip.GyroRaw() // Read raw gyro values, () → (int16, int16, int16, error)
	// returns signed 16-bit gyroscope counts
	must(err)

	must(chip.ConfigureGyro(1)) // Configure gyro range, (fullScale=0) → error
	// sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
	must(chip.ConfigureAccel(1)) // Configure accel range, (fullScale=0) → error
	// sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
	must(chip.ConfigureFilters(3, 3, true, true)) // Configure low-pass filters, (gyroDlpf, accelDlpf, gyroFilter, accelFilter) → error
	// sets *_DLPFCFG and *_FCHOICE; filter false bypasses the filter
	must(chip.ConfigureSampleRate(10, 10)) // Configure sample rate, (gyroDiv, accelDiv) → error
	// sets the dividers; ODR = 1125 Hz / (1 + div)
	must(chip.SetSensors(true, true)) // Power sensors, (accel, gyro) → error
	// writes PWR_MGMT_2 DISABLE_ACCEL / DISABLE_GYRO
	must(chip.SetLowPower(false, false, 0, 0)) // Configure duty-cycled mode, (accel, gyro, accelAvg, gyroAvg) → error
	// writes LP_CONFIG and averaging; LP_EN is set last
	must(chip.SetGyroOffset(0, 0, 0)) // Set gyro offsets, (x, y, z rad/s) → error
	// writes the 16-bit offset-cancellation registers (0.0305 dps/LSB)
	ox, oy, oz, err := chip.GetAccelOffset() // Read accel offset codes, () → (int16, int16, int16, error)
	// reads the factory-trimmed 15-bit offsets (0.98 mg/LSB)
	must(err)
	must(chip.SetAccelOffset(ox, oy, oz)) // Write accel offset codes, (x, y, z) → error
	// writes the 15-bit offsets back unchanged

	must(chip.EnableMag()) // Enable magnetometer, () → error
	// starts the I²C master and reads the AK09916 block continuously
	mx, my, mz, err := chip.Mag() // Read 3-axis magnetic field, () → (float32, float32, float32 µT, error)
	// converts raw counts at 0.15 µT/LSB; errors on HOFL
	must(err)
	rmx, rmy, rmz, err := chip.MagRaw() // Read raw mag values, () → (int16, int16, int16, error)
	// returns signed 16-bit magnetometer counts
	must(err)
	magReady, err := chip.MagDataReady() // Check magnetometer data ready, () → (bool, error)
	// reads ST1.DRDY from the shadowed block
	must(err)
	must(chip.MagSetMode(8)) // Set magnetometer mode, (mode) → error
	// writes CNTL2: 0=off, 1=single, 2/4/6/8=continuous 1-4
	who, err := chip.AuxReadByte(0x0C, 0x01) // Read auxiliary byte, (address, register) → (byte, error)
	// reads AK09916 WIA2 (0x09) through I²C-master slave 4
	must(err)
	must(chip.AuxWriteByte(0x0C, 0x31, 0x08)) // Write auxiliary byte, (address, register, value) → error
	// writes AK09916 CNTL2 through slave 4

	ready, err := chip.DataReady() // Check data ready flag, () → (bool, error)
	// reads INT_STATUS_1.RAW_DATA_0_RDY_INT
	must(err)

	must(chip.EnableFifo(true, true, false)) // Enable FIFO sources, (accel, gyro, temp) → error
	// sets FIFO_EN_2 and USER_CTRL.FIFO_EN, then resets the FIFO
	must(chip.SetFifoMode(false)) // Select FIFO mode, (snapshot) → error
	// false = stream (overwrite oldest), true = snapshot (stop when full)
	time.Sleep(50 * time.Millisecond)
	count, err := chip.FifoCount() // Read FIFO byte count, () → (uint16, error)
	// reads FIFO_COUNTH/L: number of bytes available
	must(err)
	data, err := chip.ReadFifo(-1) // Read FIFO data, (count; <0 = all) → ([]byte, error)
	// reads available bytes from FIFO_R_W in chunks
	must(err)
	must(chip.ResetFifo()) // Reset FIFO buffer, () → error
	// asserts and de-asserts FIFO_RST

	must(chip.SetWakeOnMotion(200, true)) // Arm wake-on-motion, (thresholdMg, enabled) → error
	// writes ACCEL_WOM_THR (4 mg/LSB) and ACCEL_INTEL_CTRL
	must(chip.SetInterruptPin(false, false, true, false)) // Configure INT1 pin, (activeLow, openDrain, latch, anyReadClears) → error
	// writes INT_PIN_CFG
	must(chip.EnableInterrupt(imu.ICM20948SourceDataReady)) // Enable interrupt source, (source) → error
	// sets the source's enable bit in INT_ENABLE*
	status, err := chip.PollInterrupt() // Read interrupt status, () → (uint8, error)
	// reads INT_STATUS, _1, _2 (clearing them) into source bits
	must(err)
	must(chip.DisableInterrupt(imu.ICM20948SourceDataReady)) // Disable interrupt source, (source) → error
	// clears the source's enable bit
	must(chip.OnInterrupt(func(s uint8) { fmt.Println("INT", s) })) // Subscribe to INT1, (callback) → error
	// callback receives the status from PollInterrupt
	must(chip.OffInterrupt()) // Unsubscribe, () → error
	// removes the callback

	stAccel, stGyro, err := chip.SelfTest() // Run self-test, () → ([3]float32, [3]float32, error)
	// raw response (enabled minus disabled) per axis; no pass/fail
	must(err)
	tbc, err := chip.ReadTimebaseCorrection() // Read PLL time-base correction, () → (float32 %, error)
	// reads TIMEBASE_CORRECTION_PLL (0.079 %/LSB, signed)
	must(err)

	must(chip.SetSleep(true)) // Enter sleep mode, (sleep) → error
	// sets SLEEP bit in PWR_MGMT_1
	time.Sleep(10 * time.Millisecond)
	must(chip.SetSleep(false)) // Wake from sleep, (sleep) → error
	// clears SLEEP bit in PWR_MGMT_1

	fmt.Printf("accel=(%.2f %.2f %.2f) m/s2  gyro=(%.3f %.3f %.3f) rad/s\n", ax, ay, az, gx, gy, gz)
	fmt.Printf("temperature=%.2f C  mag=(%.1f %.1f %.1f) uT  ready=%v\n", t, mx, my, mz, ready)
	_, _, _, _, _, _ = rax, ray, raz, rgx, rgy, rgz
	_, _, _, _, _, _ = accel, gyro, temp, rmx, rmy, rmz
	_, _, _, _, _, _ = magReady, who, count, data, status, stAccel
	_, _ = stGyro, tbc
	must(chip.Reset()) // Reset the device, () → error
	// DEVICE_RESET and wait; construct a new driver afterwards
}
