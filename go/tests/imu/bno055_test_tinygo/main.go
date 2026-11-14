//go:build tinygo

// BNO055 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a BNO055 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func abs32(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	conn := connection.NewI2CConnection(i2c, 0x29, nil, nil)
	chip, err := imu.NewBNO055Full(conn)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	rev, err := chip.Revisions()
	check("revisions_no_error", err == nil)
	check("chip_id", rev.ChipID == 0xA0)
	check("sensor_ids", rev.AccID == 0xFB && rev.MagID == 0x32 && rev.GyrID == 0x0F)
	mode, err := chip.GetMode()
	check("mode_ndof", err == nil && mode == 0x0C)

	h, r, p, err := chip.Euler()
	check("euler_range", err == nil && h >= 0 && h < 6.5 && r > -1.6 && r < 1.6 && p > -3.2 && p < 3.2)
	qw, qx, qy, qz, err := chip.Quaternion()
	q := qw*qw + qx*qx + qy*qy + qz*qz
	check("quaternion_unit", err == nil && q > 0.9 && q < 1.1)
	gx, gy, gz, err := chip.Gravity()
	g2 := gx*gx + gy*gy + gz*gz
	check("gravity_1g", err == nil && g2 > 72 && g2 < 121)
	lx, ly, lz, err := chip.LinearAcceleration()
	check("linear_acceleration_small", err == nil && abs32(lx) < 5 && abs32(ly) < 5 && abs32(lz) < 5)
	t, err := chip.Temperature()
	check("temperature_range", err == nil && t > -40 && t < 85)
	cs, cg, ca, cm, err := chip.CalibrationStatus()
	check("calibration_status_range", err == nil && cs <= 3 && cg <= 3 && ca <= 3 && cm <= 3)
	status, errCode, err := chip.SystemStatus()
	check("system_status", err == nil && status == 5 && errCode == 0)
	uid, err := chip.UniqueID()
	check("unique_id_length", err == nil && len(uid) == 16)
	blob, err := chip.GetCalibration()
	check("calibration_length", err == nil && len(blob) == 22)

	check("set_mode_amg", chip.SetMode(imu.BNO055ModeAMG) == nil)
	mode, _ = chip.GetMode()
	check("mode_amg", mode == 0x07)
	ax, ay, az, err := chip.Acceleration()
	a2 := ax*ax + ay*ay + az*az
	check("accel_magnitude", err == nil && a2 > 64 && a2 < 132)
	mx, my, mz, err := chip.MagneticField()
	m2 := mx*mx + my*my + mz*mz
	check("mag_field_range", err == nil && m2 > 25 && m2 < 40000)
	check("set_mode_ndof", chip.SetMode(imu.BNO055ModeNDOF) == nil)
	mode, _ = chip.GetMode()
	check("mode_ndof_again", mode == 0x0C)

	st, err := chip.SelfTest()
	check("self_test_passes", err == nil && st.Acc && st.Mag && st.Gyr && st.MCU)
	mode, _ = chip.GetMode()
	check("mode_after_self_test", mode == 0x0C)

	check("enable_interrupt", chip.EnableInterrupt(imu.BNO055SourceAccBsxDrdy) == nil)
	_, err = chip.PollInterrupt()
	check("poll_interrupt", err == nil)
	check("disable_interrupt", chip.DisableInterrupt(imu.BNO055SourceAccBsxDrdy) == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
