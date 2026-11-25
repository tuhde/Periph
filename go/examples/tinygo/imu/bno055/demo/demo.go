//go:build tinygo

// BNO055 demo example — TinyGo / Raspberry Pi Pico W.
//
// Orientation monitor: revisions and unique ID, a monitor loop until fully calibrated, calibration save/restore, self-test, raw sensors in AMG mode and the interrupt API.
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
		Frequency: 100_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x29, nil, nil) // Create I2C connection, (i2c, addr=0x29) → (*I2CConnection)

	chip, err := imu.NewBNO055Full(conn) // Create BNO055 driver, (connection) → (*BNO055Full, error)
	must(err)

	// --- Identify the part ---
	info, err := chip.Revisions() // Read revisions, () → (BNO055Revisions, error)
	must(err)
	fmt.Printf("chip 0x%02X, SW %d.%d, bootloader %d\n", info.ChipID, info.SwRevision>>8, info.SwRevision&0xFF, info.Bootloader)
	uid, err := chip.UniqueID() // Read unique ID, () → ([]byte, error)
	must(err)
	fmt.Printf("unique id %x\n", uid)

	// --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
	for {
		h, r, p, err := chip.Euler() // Read fused Euler angles, () → (float32, float32, float32 rad, error)
		must(err)
		qw, qx, qy, qz, err := chip.Quaternion() // Read orientation quaternion, () → (float32, float32, float32, float32, error)
		must(err)
		lx, ly, lz, err := chip.LinearAcceleration() // Read gravity-free acceleration, () → (float32, float32, float32 m/s², error)
		must(err)
		gx, gy, gz, err := chip.Gravity() // Read gravity vector, () → (float32, float32, float32 m/s², error)
		must(err)
		cs, cg, ca, cm, err := chip.CalibrationStatus() // Read calibration status, () → (sys, gyr, acc, mag uint8, error)
		must(err)
		fmt.Printf("euler %.2f %.2f %.2f  quat %.3f %.3f %.3f %.3f  lin %.2f %.2f %.2f  grav %.2f %.2f %.2f  cal %d%d%d%d\n",
			h, r, p, qw, qx, qy, qz, lx, ly, lz, gx, gy, gz, cs, cg, ca, cm)
		done, err := chip.IsCalibrated() // Check full calibration, () → (bool, error)
		must(err)
		if done {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	// --- Save the calibration, relative mode for 5 s, restore it ---
	blob, err := chip.GetCalibration() // Read offsets and radii, () → ([]byte, error)
	must(err)
	fmt.Printf("calibration %x\n", blob)
	must(chip.SetMode(imu.BNO055ModeIMU)) // Select operation mode, (mode) → error
	mode, err := chip.GetMode()           // Read operation mode, () → (uint8, error)
	must(err)
	fmt.Println("mode", mode)
	time.Sleep(5 * time.Second)
	must(chip.SetMode(imu.BNO055ModeNDOF)) // Select operation mode, (mode) → error
	must(chip.SetCalibration(blob))        // Restore offsets and radii, (data) → error

	// --- Self-test, temperature and configuration ---
	st, err := chip.SelfTest() // Run self-test, () → (BNO055SelfTestResult, error)
	must(err)
	fmt.Printf("self-test %+v\n", st)
	t, err := chip.Temperature() // Read temperature, () → (int8 °C, error)
	must(err)
	fmt.Println("temperature", t, "C")
	must(chip.SetTemperatureSource(imu.BNO055TempSourceGyroscope)) // Select temperature sensor, (source) → error
	must(chip.SetAxisRemap(0x24, 0x00))                            // Remap axes, (config, sign) → error
	must(chip.SetPowerMode(imu.BNO055PowerNormal))                 // Select power mode, (mode) → error
	must(chip.UseExternalCrystal(false))                           // Select clock source, (on) → error

	// --- Raw sensors in AMG mode ---
	must(chip.SetMode(imu.BNO055ModeAMG))  // Select operation mode, (mode) → error
	ax, ay, az, err := chip.Acceleration() // Read raw accelerometer, () → (float32, float32, float32 m/s², error)
	must(err)
	mx, my, mz, err := chip.MagneticField() // Read raw magnetometer, () → (float32, float32, float32 µT, error)
	must(err)
	rx, ry, rz, err := chip.AngularRate() // Read raw gyroscope, () → (float32, float32, float32 rad/s, error)
	must(err)
	fmt.Printf("acc %.2f %.2f %.2f  mag %.1f %.1f %.1f  gyr %.3f %.3f %.3f\n", ax, ay, az, mx, my, mz, rx, ry, rz)

	// --- Interrupts: accelerometer/fusion data ready ---
	must(chip.SetMode(imu.BNO055ModeNDOF))                                 // Select operation mode, (mode) → error
	must(chip.EnableInterrupt(imu.BNO055SourceAccBsxDrdy))                 // Enable interrupt source, (source) → error
	must(chip.OnInterrupt(func(s byte) { fmt.Printf("INT 0x%02X\n", s) })) // Subscribe to interrupts, (callback) → error
	time.Sleep(500 * time.Millisecond)
	must(chip.OffInterrupt())                               // Unsubscribe, () → error
	must(chip.DisableInterrupt(imu.BNO055SourceAccBsxDrdy)) // Disable interrupt source, (source) → error
	ints, err := chip.PollInterrupt()                       // Poll interrupt status, () → (uint8, error)
	must(err)
	fmt.Printf("interrupt status 0x%02X\n", ints)
	status, errCode, err := chip.SystemStatus() // Read system status, () → (status, error uint8, error)
	must(err)
	fmt.Printf("system status %d, error %d\n", status, errCode)
	must(chip.Reset()) // Reset the chip, () → error

	for {
		time.Sleep(time.Second)
	}
}
