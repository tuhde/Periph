//go:build linux && !tinygo

// HTU31D hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the HTU31D check sequence. Prints
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
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

	chip := humidity.NewHTU31DFull(conn)

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
	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("read_temperature_range", t > -40 && t < 125)
	check("read_humidity_range", rh >= 0 && rh <= 100)
	h, err := chip.ReadHumidity()
	check("read_humidity_single", err == nil && h >= 0 && h <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && tc > -40 && tc < 125)
	_, _, err = chip.ReadRaw()
	check("read_raw", err == nil)
	dew, err := chip.DewPoint()
	check("dew_point_below_temperature", err == nil && dew <= tc+1)
	sn, err := chip.SerialNumber()
	check("serial_number_24bit", err == nil && sn <= 0xFFFFFF)
	diag, err := chip.Diagnostic()
	check("diagnostic_no_nvm_error", err == nil && diag&humidity.HTU31DDiagNVMErr == 0)
	for osr := uint8(0); osr < 4; osr++ {
		check("set_resolution", chip.SetResolution(osr, osr) == nil)
		a, b := chip.GetResolution()
		check("resolution_roundtrip", a == osr && b == osr)
		_, r, err := chip.Read()
		check("read_at_osr", err == nil && r >= 0 && r <= 100)
	}
	chip.SetResolution(3, 3)
	check("conversion_time_osr33", chip.ConversionTime() > 0.0203 && chip.ConversionTime() < 0.0204)
	check("start_conversion", chip.StartConversion() == nil)
	time.Sleep(30 * time.Millisecond)
	_, r, err := chip.ReadResult()
	check("read_result", err == nil && r >= 0 && r <= 100)
	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.IsHeaterOn()
	check("heater_on_readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.IsHeaterOn()
	check("heater_off_readback", err == nil && !on)
	check("software_reset", chip.SoftReset() == nil)
	_, r, err = chip.Read()
	check("read_after_reset", err == nil && r >= 0 && r <= 100)

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
