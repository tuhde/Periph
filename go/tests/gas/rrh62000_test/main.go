//go:build linux && !tinygo

// RRH62000 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the RRH62000 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x69"), 0, 8)
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

	chip, _ := gas.NewRRH62000Full(conn)

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
	var terr error
	_, _, terr = chip.FirmwareVersion()
	check("firmware_version", terr == nil)
	_, _, _, terr = chip.AlgorithmVersion()
	check("algorithm_version", terr == nil)
	uid, terr := chip.UniqueID()
	check("unique_id", terr == nil && len(uid) == 6)
	m, terr := chip.Read()
	check("read", terr == nil)
	check("read_pm", m.PM2_5UgM3 >= 0 && m.PM2_5UgM3 <= 1000)
	check("read_temperature", m.TemperatureC >= -20 && m.TemperatureC <= 60)
	check("read_humidity", m.HumidityRH >= 0 && m.HumidityRH <= 100)
	check("read_iaq", m.IAQ >= 0 && m.IAQ <= 5)
	d, terr := chip.ReadAll()
	check("read_all", terr == nil && d.Particles.NC0_3 >= d.Particles.NC0_5)
	st, terr := chip.ReadStatus()
	check("read_status", terr == nil && !st.Fan)
	_, terr = chip.ReadParticles()
	check("read_particles", terr == nil)
	_, terr = chip.DataReady()
	check("data_ready", terr == nil)
	avg, _ := chip.GetMovingAverage()
	v, terr := 0, chip.SetMovingAverage(20)
	if terr == nil {
		v, terr = chip.GetMovingAverage()
	}
	check("moving_average", terr == nil && v == 20)
	_ = chip.SetMovingAverage(avg)
	fan, _ := chip.GetFanSpeed()
	v, terr = 0, chip.SetFanSpeed(70)
	if terr == nil {
		v, terr = chip.GetFanSpeed()
	}
	check("fan_speed", terr == nil && v == 70)
	_ = chip.SetFanSpeed(fan)
	interval, _ := chip.GetCleaningInterval()
	v, terr = 0, chip.SetCleaningInterval(86400)
	if terr == nil {
		v, terr = chip.GetCleaningInterval()
	}
	check("cleaning_interval", terr == nil && v == 86400)
	_ = chip.SetCleaningInterval(interval)
	ct, _ := chip.GetCleaningTime()
	v, terr = 0, chip.SetCleaningTime(12)
	if terr == nil {
		v, terr = chip.GetCleaningTime()
	}
	check("cleaning_time", terr == nil && v == 12)
	_ = chip.SetCleaningTime(ct)
	ohms, terr := chip.ReadMoxResistance()
	check("mox_resistance", terr == nil && ohms > 0)
	_, terr = chip.CleaningDone()
	check("cleaning_done", terr == nil)
	check("sleep", chip.Sleep() == nil)
	time.Sleep(3 * time.Second)
	check("wake_read", chip.Wake() == nil && func() bool { _, e := chip.Read(); return e == nil }())
	check("soft_reset_read", chip.SoftReset() == nil && func() bool { _, e := chip.Read(); return e == nil }())

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
