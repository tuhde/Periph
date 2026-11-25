//go:build linux && !tinygo

// ICM20948 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the ICM20948 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := imu.NewICM20948Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	ax, ay, az, err := chip.Accel()
	check("accel_no_error", err == nil)
	check("accel_plausible", err == nil && ax > -200 && ax < 200 && ay > -200 && ay < 200 && az > -200 && az < 200)
	check("gravity_magnitude", err == nil && math.Sqrt(float64(ax*ax+ay*ay+az*az)) > 5 && math.Sqrt(float64(ax*ax+ay*ay+az*az)) < 15)

	gx, gy, gz, err := chip.Gyro()
	check("gyro_no_error", err == nil)
	check("gyro_plausible", err == nil && gx > -100 && gx < 100 && gy > -100 && gy < 100 && gz > -100 && gz < 100)

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t > -40 && t < 85)

	_, _, tc, err := chip.ReadAll()
	check("read_all_temperature_range", err == nil && tc > -40 && tc < 85)

	rax, ray, raz, err := chip.AccelRaw()
	rawMag := math.Sqrt(float64(rax)*float64(rax) + float64(ray)*float64(ray) + float64(raz)*float64(raz))
	check("accel_raw_gravity", err == nil && rawMag > 8000 && rawMag < 24000)
	_, _, _, err = chip.GyroRaw()
	check("gyro_raw_no_error", err == nil)

	check("configure_gyro", chip.ConfigureGyro(1) == nil)
	check("configure_accel", chip.ConfigureAccel(1) == nil)
	ax2, _, _, err := chip.Accel()
	check("accel_after_reconfig", err == nil && ax2 > -200 && ax2 < 200)

	check("configure_filters", chip.ConfigureFilters(4, 4, true, true) == nil)
	check("configure_sample_rate", chip.ConfigureSampleRate(9, 9) == nil)
	ox, oy, oz, err := chip.GetAccelOffset()
	check("accel_offset_readable", err == nil && ox >= -16384 && oy >= -16384 && oz >= -16384)

	check("enable_mag", chip.EnableMag() == nil)
	time.Sleep(150 * time.Millisecond)
	mx, my, mz, err := chip.Mag()
	mMag := math.Sqrt(float64(mx*mx + my*my + mz*mz))
	check("mag_magnitude", err == nil && mMag > 5 && mMag < 150)
	who, err := chip.AuxReadByte(0x0C, 0x01)
	check("aux_read_wia2", err == nil && who == 0x09)

	check("set_sleep", chip.SetSleep(true) == nil)
	time.Sleep(10 * time.Millisecond)
	check("clear_sleep", chip.SetSleep(false) == nil)
	time.Sleep(50 * time.Millisecond)
	ax3, _, _, err := chip.Accel()
	check("accel_after_wake", err == nil && ax3 > -200 && ax3 < 200)

	check("enable_fifo", chip.EnableFifo(true, true, false) == nil)
	time.Sleep(50 * time.Millisecond)
	count, err := chip.FifoCount()
	check("fifo_count_positive", err == nil && count > 0)
	data, err := chip.ReadFifo(-1)
	check("read_fifo_returns_bytes", err == nil && len(data) > 0)
	check("reset_fifo", chip.ResetFifo() == nil)

	check("set_wake_on_motion", chip.SetWakeOnMotion(200, true) == nil)
	status, err := chip.PollInterrupt()
	check("poll_interrupt_callable", err == nil && status <= 0x1F)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
