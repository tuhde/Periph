//go:build linux && !tinygo

// BH1750 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the BH1750 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x23"), 0, 8)
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

	chip := light.NewBH1750Full(conn)

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
	inRange := func(v float32) bool { return v >= 0 && v <= 120000 }
	lux, err := chip.Read()
	check("read_range", err == nil && inRange(lux))
	raw, err := chip.ReadRaw()
	check("read_raw_range", err == nil && raw <= 65535)
	for _, m := range []struct {
		name string
		mode light.BH1750Mode
	}{{"h", light.BH1750ModeH}, {"h2", light.BH1750ModeH2}, {"l", light.BH1750ModeL}} {
		v, err := chip.MeasureOnce(m.mode)
		check("measure_once_"+m.name, err == nil && inRange(v))
	}
	check("lux_per_count_default", math.Abs(float64(chip.LuxPerCount(light.BH1750ModeH))-1/1.2) < 1e-5)
	check("measurement_time_default", math.Abs(float64(chip.MeasurementTime(light.BH1750ModeH))-0.180) < 1e-5)
	check("start_continuous", chip.StartContinuous(light.BH1750ModeH) == nil)
	time.Sleep(200 * time.Millisecond)
	c, err := chip.ReadContinuous()
	check("read_continuous_range", err == nil && inRange(c))
	check("set_measurement_time", chip.SetMeasurementTime(138) == nil)
	check("mtreg_roundtrip", chip.GetMeasurementTimeRegister() == 138)
	check("measurement_time_scaled", math.Abs(float64(chip.MeasurementTime(light.BH1750ModeH))-0.360) < 1e-5)
	v, err := chip.MeasureOnce(light.BH1750ModeH)
	check("read_with_mtreg", err == nil && inRange(v))
	check("set_sensitivity", chip.SetSensitivity(1.0) == nil)
	check("sensitivity_default", chip.GetMeasurementTimeRegister() == 69)
	chip.PowerDown()
	chip.PowerOn()
	check("reset", chip.Reset() == nil)
	v, err = chip.MeasureOnce(light.BH1750ModeH)
	check("read_after_reset", err == nil && inRange(v))
	chip.PowerDown()

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
