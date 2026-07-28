//go:build linux && !tinygo

// MPU9250 complete example — Linux host.
//
// Exercises every method in the MPU9250Full API: configuration,
// temperature, raw readings, data-ready polling, magnetometer enable
// and read, sleep control, and FIFO management.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	// The MPU9250 exposes the on-board AK8963 magnetometer at I2C
	// address 0x0C through its I2C bypass. Construct a connection
	// factory that opens a second file descriptor on the same bus for
	// any requested address.
	magFactory := func(a uint8) (connection.Connection, error) {
		return connection.NewI2CConnection(bus, a, nil, nil) // Open second I2C fd for the AK8963, (bus, addr) → (*I2CConnection, error)
	}

	chip, err := imu.NewMPU9250Full(conn, magFactory) // Create MPU9250 driver with magnetometer, (connection, magFactory) → (*MPU9250Full, error)
	if err != nil {
		panic(err)
	}
	defer chip.Close()

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

	if err := chip.ConfigureDLPF(3, 3); err != nil { // Set gyro+accel DLPF, (gyro_dlpf=0–6, accel_dlpf=0–6) → error
		panic(err)
	}
	// 0=260/256 Hz, 1=184/188 Hz, 2=92/98 Hz, 3=41/42 Hz, 4=20/20, 5=10/10, 6=5/5

	if err := chip.ConfigureSampleRate(4); err != nil { // Set sample rate divider, (divider=0–255) → error
		panic(err)
	}
	// 200 Hz output = 1 kHz / (1 + 4)

	temp, err := chip.Temperature() // Read die temperature, () → (float32 °C, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("temperature: %.2f C\n", temp) // raw 16-bit signed / 333.87 + 21.0

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
	fmt.Printf("data_ready: %v\n", ready) // reads RAW_DATA_RDY_INT bit from INT_STATUS

	if err := chip.EnableMag(16, 6); err != nil { // Initialise magnetometer, (bits=14|16, mode=1|2|6) → error
		panic(err)
	}
	// 16-bit, 100 Hz continuous measurement mode 2

	mx, my, mz, err := chip.Mag() // Read 3-axis magnetic field, () → (float32 µT, float32 µT, float32 µT, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mag: (%.2f %.2f %.2f) uT\n", mx, my, mz) // applies factory ASA calibration

	rmx, rmy, rmz, err := chip.MagRaw() // Read raw 16-bit magnetometer, () → (int16, int16, int16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mag_raw: (%d %d %d)\n", rmx, rmy, rmz) // big-endian signed from AK8963 0x03-0x08

	if err := chip.SetSleep(false); err != nil { // Set/clear sleep, (sleep=true|false) → error
		panic(err)
	}
	// SLEEP bit in PWR_MGMT_1; false wakes the device

	if err := chip.EnableFIFO(true, true, false); err != nil { // Configure FIFO sources, (gyro, accel, temp) → error
		panic(err)
	}
	// enables gyro+accel in FIFO, sets FIFO_EN in USER_CTRL

	if err := chip.ResetFIFO(); err != nil { // Reset FIFO buffer, () → error
		panic(err)
	}
	// asserts FIFO_RST bit in USER_CTRL
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
