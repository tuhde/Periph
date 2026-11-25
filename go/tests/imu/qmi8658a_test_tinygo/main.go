//go:build tinygo

// QMI8658A hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an QMI8658A on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"math"
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
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewI2CConnection(i2c, 0x6B, nil, nil)
	chip, err := imu.NewQMI8658AFull(conn)
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

	ax, ay, az, err := chip.Accel()
	check("accel_no_error", err == nil)
	gmag := math.Sqrt(float64(ax*ax + ay*ay + az*az))
	check("gravity_magnitude", err == nil && gmag > 7 && gmag < 12.6)

	gx, gy, gz, err := chip.Gyro()
	check("gyro_no_error", err == nil)
	check("gyro_plausible", err == nil && math.Abs(float64(gx)) < 10 && math.Abs(float64(gy)) < 10 && math.Abs(float64(gz)) < 10)

	_, _, tc, err := chip.ReadAll()
	check("read_all_temperature_range", err == nil && tc > -40 && tc < 85)
	t, err := chip.Temperature()
	check("temperature_matches", err == nil && math.Abs(float64(t-tc)) < 5)

	rax, ray, raz, err := chip.AccelRaw()
	rawMag := math.Sqrt(float64(rax)*float64(rax) + float64(ray)*float64(ray) + float64(raz)*float64(raz))
	check("accel_raw_gravity", err == nil && rawMag > 8000 && rawMag < 24000)
	_, _, _, err = chip.GyroRaw()
	check("gyro_raw_no_error", err == nil)

	ts1, err := chip.Timestamp()
	time.Sleep(100 * time.Millisecond)
	ts2, err2 := chip.Timestamp()
	check("timestamp_advances", err == nil && err2 == nil && ts1 != ts2)

	check("configure_accel", chip.ConfigureAccel(1, 5) == nil)
	check("configure_gyro", chip.ConfigureGyro(5, 5) == nil)
	time.Sleep(200 * time.Millisecond)
	bx, by, bz, err := chip.Accel()
	bmag := math.Sqrt(float64(bx*bx + by*by + bz*bz))
	check("accel_after_reconfig", err == nil && bmag > 7 && bmag < 12.6)
	check("restore_accel", chip.ConfigureAccel(0, 5) == nil)
	check("restore_gyro", chip.ConfigureGyro(4, 5) == nil)
	check("configure_filters", chip.ConfigureFilters(true, 3, true, 3) == nil)
	time.Sleep(200 * time.Millisecond)

	check("configure_fifo", chip.ConfigureFifo(2, 1, 8) == nil)
	time.Sleep(300 * time.Millisecond)
	count, err := chip.FifoCount()
	check("fifo_count_positive", err == nil && count > 0)
	data, err := chip.ReadFifo(-1)
	check("read_fifo_whole_frames", err == nil && len(data) > 0 && len(data)%12 == 0)
	check("reset_fifo", chip.ResetFifo() == nil)
	check("fifo_bypass", chip.ConfigureFifo(0, 3, 0) == nil)
	time.Sleep(100 * time.Millisecond)

	_, fw, usid, err := chip.DeviceInfo()
	check("device_info", err == nil && len(fw) == 3 && len(usid) == 6)
	check("set_sensors", chip.SetSensors(true, true, false) == nil)

	status, err := chip.PollInterrupt()
	check("poll_interrupt_callable", err == nil && status <= 0x1FF)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
