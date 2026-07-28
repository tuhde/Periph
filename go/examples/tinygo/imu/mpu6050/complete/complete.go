//go:build tinygo

// MPU6050 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the MPU6050Full API: configuration,
// temperature, raw readings, data-ready polling, sleep/standby, and
// FIFO management.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil)        // Create I2C connection, (i2c, addr=0x68) → (*I2CConnection)
	chip, err := imu.NewMPU6050Full(conn)               // Create MPU6050 driver, (connection) → (*MPU6050Full, error)
	if err != nil {
		panic(err)
	}

	ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32 m/s², float32 m/s², float32 m/s², error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("accel: (%6.2f %6.2f %6.2f) m/s2\n", ax, ay, az) // converts raw 16-bit registers to m/s^2

	gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32 rad/s, float32 rad/s, float32 rad/s, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gyro: (%7.3f %7.3f %7.3f) rad/s\n", gx, gy, gz) // converts raw 16-bit registers to rad/s

	if err := chip.ConfigureGyro(1); err != nil { // Set gyroscope full-scale, (full_scale=0–3) → error
		panic(err)
	}
	// 0=±250, 1=±500, 2=±1000, 3=±2000 dps

	if err := chip.ConfigureAccel(1); err != nil { // Set accelerometer full-scale, (full_scale=0–3) → error
		panic(err)
	}
	// 0=±2g, 1=±4g, 2=±8g, 3=±16g

	if err := chip.ConfigureDLPF(3); err != nil { // Set digital low-pass filter, (dlpf=0–6) → error
		panic(err)
	}
	// 0=260/256 Hz, 1=184/188 Hz, 2=94/98 Hz, 3=44/42 Hz (default)

	if err := chip.ConfigureSampleRate(4); err != nil { // Set sample rate divider, (divider=0–255) → error
		panic(err)
	}
	// 200 Hz output = 1 kHz / (1 + 4)

	temp, err := chip.Temperature() // Read die temperature, () → (float32 °C, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("temperature: %.2f C\n", temp) // raw 16-bit signed / 340 + 36.53

	rax, ray, raz, err := chip.AccelRaw() // Read raw 16-bit accelerometer, () → (int16, int16, int16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("accel_raw: (%d %d %d)\n", rax, ray, raz) // big-endian signed from registers 0x3B-0x40

	rgx, rgy, rgz, err := chip.GyroRaw() // Read raw 16-bit gyroscope, () → (int16, int16, int16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gyro_raw: (%d %d %d)\n", rgx, rgy, rgz) // big-endian signed from registers 0x43-0x48

	ready, err := chip.DataReady() // Check data-ready interrupt, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("data_ready: %v\n", ready) // reads DATA_RDY_INT bit from INT_STATUS

	if err := chip.SetSleep(false); err != nil { // Set/clear sleep, (sleep=true|false) → error
		panic(err)
	}
	// SLEEP bit in PWR_MGMT_1; false wakes the device

	if err := chip.SetStandby(false, false, false, false, false, false); err != nil { // Put axes in standby, (xa, ya, za, xg, yg, zg) → error
		panic(err)
	}
	// writes PWR_MGMT_2 with per-axis standby bits

	if err := chip.EnableFIFO(true, true, false); err != nil { // Configure FIFO sources, (gyro, accel, temp) → error
		panic(err)
	}
	// enables gyro+accel in FIFO, sets FIFO_EN in USER_CTRL

	if err := chip.ResetFIFO(); err != nil { // Reset FIFO buffer, () → error
		panic(err)
	}
	// asserts FIFO_RST bit in USER_CTRL

	time.Sleep(100 * time.Millisecond)
}
