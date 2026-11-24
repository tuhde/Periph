//go:build linux && !tinygo

// HTS221 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the HTS221 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5F"), 0, 8)
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

	chip := humidity.NewHTS221Full(conn)

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

	check("init", chip.Init() == nil)
	id, err := chip.WhoAmI()
	check("who_am_i", err == nil && id == 0xBC)
	cal := chip.ReadCalibration()
	check("calibration_plausible", cal.H1rH > cal.H0rH && cal.T1degC > cal.T0degC)
	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("read_temperature_range", t > -40 && t < 85)
	check("read_humidity_range", rh >= 0 && rh <= 100)
	h, err := chip.ReadHumidity()
	check("read_humidity_single", err == nil && h >= 0 && h <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && tc > -40 && tc < 85)
	dew, err := chip.DewPoint()
	check("dew_point_below_temperature", err == nil && dew <= tc+1)
	check("set_averaging", chip.SetAveraging(32, 64) == nil)
	ta, ha, err := chip.GetAveraging()
	check("averaging_roundtrip", err == nil && ta == 32 && ha == 64)
	check("set_averaging default", chip.SetAveraging(16, 32) == nil)
	check("set_output_data_rate", chip.SetOutputDataRate(1) == nil)
	hz, err := chip.GetOutputDataRate()
	check("odr_roundtrip", err == nil && hz == 1)
	time.Sleep(1500 * time.Millisecond)
	_, crh, err := chip.ReadContinuous()
	check("continuous_read", err == nil && crh >= 0 && crh <= 100)
	check("set_output_data_rate one-shot", chip.SetOutputDataRate(0) == nil)
	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.IsHeaterOn()
	check("heater_on_readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.IsHeaterOn()
	check("heater_off_readback", err == nil && !on)

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
