//go:build linux && !tinygo

// PN532 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the PN532 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x24"), 0, 8)
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

	chip, _ := rfid.NewPN532Full(conn)

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
	fw, err := chip.FirmwareVersion()
	check("firmware_ic", err == nil && fw.IC == 0x32)
	check("firmware_support_a", fw.ISO14443A)
	gs, err := chip.GeneralStatus()
	check("general_status_targets", err == nil && gs.Targets <= 2)
	ok, err := chip.SelfTest(0x00)
	check("self_test_echo", err == nil && ok)
	ok, err = chip.SelfTest(0x01)
	check("self_test_rom", err == nil && ok)
	r, err := chip.SendCommand(0x02, nil, time.Second)
	check("send_command", err == nil && len(r) == 4 && r[0] == 0x32)
	check("start_command", chip.StartCommand(0x02, nil) == nil)
	ok, err = chip.WaitReady(time.Second)
	check("wait_ready", err == nil && ok)
	ok, err = chip.PollInterrupt()
	check("poll_interrupt", err == nil && ok)
	r, err = chip.ReadResponse(0x02, time.Second)
	check("read_response", err == nil && len(r) == 4 && r[0] == 0x32)
	reg, err := chip.ReadRegister(0x6302)
	check("read_register", err == nil)
	check("write_register", chip.WriteRegister(0x6302, reg) == nil)
	reg2, err := chip.ReadRegister(0x6302)
	check("register_roundtrip", err == nil && reg2 == reg)
	check("rf_field_toggle", chip.SetRFField(false, false) == nil && chip.SetRFField(true, false) == nil)
	check("set_max_retries", chip.SetMaxRetries(2, 0xFF, 1) == nil)
	start := time.Now()
	uid, err := chip.ReadUID(time.Second)
	check("read_uid_returns", err == nil && (uid == nil || len(uid) == 4 || len(uid) == 7 || len(uid) == 10))
	check("read_uid_bounded", time.Since(start) < 2*time.Second)
	targets, err := chip.ListPassiveTargets(rfid.PN532Baud106A, 1, nil, time.Second)
	check("list_passive_targets", err == nil)
	if len(targets) > 0 {
		_ = chip.Release(targets[0].Tg)
	}
	check("power_down", chip.PowerDown(0x20) == nil)
	time.Sleep(10 * time.Millisecond)
	fw, err = chip.FirmwareVersion()
	check("wake_after_power_down", err == nil && fw.IC == 0x32)

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
