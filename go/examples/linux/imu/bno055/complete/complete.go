//go:build linux && !tinygo

// BNO055 complete example — Linux host.
//
// Constructs the Full driver and calls every public method once.
package main

import (
	"fmt"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x29"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x29) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := imu.NewBNO055Full(conn) // Create BNO055 driver, (connection) → (*BNO055Full, error)
	must(err)

	h, r, p, err := chip.Euler() // Read fused Euler angles, () → (float32, float32, float32 rad, error)
	// burst read of 0x1A…0x1F, raw / 900
	must(err)
	qw, qx, qy, qz, err := chip.Quaternion() // Read orientation quaternion, () → (float32, float32, float32, float32, error)
	// raw / 16384
	must(err)
	lx, ly, lz, err := chip.LinearAcceleration() // Read gravity-free acceleration, () → (float32, float32, float32 m/s², error)
	// raw / 100
	must(err)
	gx, gy, gz, err := chip.Gravity() // Read gravity vector, () → (float32, float32, float32 m/s², error)
	// raw / 100
	must(err)
	ax, ay, az, err := chip.Acceleration() // Read raw accelerometer, () → (float32, float32, float32 m/s², error)
	// raw / 100
	must(err)
	mx, my, mz, err := chip.MagneticField() // Read raw magnetometer, () → (float32, float32, float32 µT, error)
	// raw / 16
	must(err)
	rx, ry, rz, err := chip.AngularRate() // Read raw gyroscope, () → (float32, float32, float32 rad/s, error)
	// raw / 900
	must(err)
	t, err := chip.Temperature() // Read temperature, () → (int8 °C, error)
	// signed 8 bit, 1 LSB = 1 °C
	must(err)
	cs, cg, ca, cm, err := chip.CalibrationStatus() // Read calibration status, () → (sys, gyr, acc, mag uint8, error)
	// each 0 (uncalibrated) … 3 (calibrated)
	must(err)
	done, err := chip.IsCalibrated() // Check full calibration, () → (bool, error)
	// true when all four values are 3
	must(err)
	fmt.Println(h, r, p, qw, qx, qy, qz, lx, ly, lz, gx, gy, gz, ax, ay, az, mx, my, mz, rx, ry, rz, t, cs, cg, ca, cm, done)

	mode, err := chip.GetMode() // Read operation mode, () → (uint8, error)
	// 0x0C = NDOF
	must(err)
	must(chip.SetMode(imu.BNO055ModeIMU)) // Select operation mode, (mode) → error
	// via CONFIGMODE, 19 ms + 7 ms waits
	must(chip.SetMode(imu.BNO055ModeNDOF)) // Select operation mode, (mode) → error
	// back to 9-DOF fusion

	blob, err := chip.GetCalibration() // Read offsets and radii, () → ([]byte, error)
	// 22 bytes, 0x55…0x6A, read in CONFIGMODE
	must(err)
	must(chip.SetCalibration(blob)) // Restore offsets and radii, (data) → error
	// 22 bytes, written in CONFIGMODE
	must(chip.SetAxisRemap(0x24, 0x00)) // Remap axes, (config, sign) → error
	// 0x24/0x00 = identity placement
	must(chip.SetPowerMode(imu.BNO055PowerNormal)) // Select power mode, (mode) → error
	// normal, low power or suspend
	must(chip.SetTemperatureSource(imu.BNO055TempSourceAccelerometer)) // Select temperature sensor, (source) → error
	// accelerometer or gyroscope
	must(chip.UseExternalCrystal(false)) // Select clock source, (on) → error
	// internal oscillator unless a crystal is fitted

	st, err := chip.SelfTest() // Run self-test, () → (BNO055SelfTestResult, error)
	// Acc/Mag/Gyr/MCU booleans, mode restored afterwards
	must(err)
	status, errCode, err := chip.SystemStatus() // Read system status, () → (status, error uint8, error)
	// SYS_STATUS 5 = fusion running, SYS_ERR 0 = none
	must(err)
	info, err := chip.Revisions() // Read revisions, () → (BNO055Revisions, error)
	// chip/acc/mag/gyr IDs, SW revision, bootloader
	must(err)
	uid, err := chip.UniqueID() // Read unique ID, () → ([]byte, error)
	// 16 bytes from page 1
	must(err)
	fmt.Println(mode, st, status, errCode, info, uid)

	must(chip.EnableInterrupt(imu.BNO055SourceAccBsxDrdy)) // Enable interrupt source, (source) → error
	// INT_EN and INT_MSK on page 1
	ints, err := chip.PollInterrupt() // Poll interrupt status, () → (uint8, error)
	// INT_STA, cleared on read
	must(err)
	must(chip.OnInterrupt(func(s byte) { fmt.Printf("INT status 0x%02X\n", s) })) // Subscribe to interrupts, (callback) → error
	// uses an INT pin or a 100 ms polling goroutine
	time.Sleep(500 * time.Millisecond)
	must(chip.OffInterrupt()) // Unsubscribe, () → error
	// stops the callback
	must(chip.DisableInterrupt(imu.BNO055SourceAccBsxDrdy)) // Disable interrupt source, (source) → error
	// clears the INT_EN and INT_MSK bits
	fmt.Printf("interrupt status 0x%02X\n", ints)

	must(chip.Reset()) // Reset the chip, () → error
	// RST_SYS, waits 650 ms, re-enters NDOF
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
