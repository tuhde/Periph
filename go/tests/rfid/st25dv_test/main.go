//go:build linux && !tinygo

// ST25DV hardware test — Linux host.
//
// Opens /dev/i2c-N twice (I2C_ADDR = user area, default 0x53; system area 0x57)
// and runs the ST25DV check sequence. Prints PASS/FAIL per check and ends with
// the standard ===DONE: ... === line. Exits 0 on full pass, 1 on any failure.
package main

import (
	"bytes"
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	user, err := connection.NewI2CConnectionWithWidth(bus, uint8(addr), 2, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer user.Close()
	system, err := connection.NewI2CConnectionWithWidth(bus, 0x57, 2, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer system.Close()

	chip, err := rfid.NewST25DVFull(user, system, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
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

	check("ic_reference", chip.ICReference() == 0x24 || chip.ICReference() == 0x26)
	size := chip.MemorySize()
	check("memory_size", size == 512 || size == 2048 || size == 8192)
	uid, err := chip.UID()
	check("uid", err == nil && len(uid) == 8 && uid[0] == 0xE0 && uid[1] == 0x02)
	_, err = chip.ICRevision()
	check("ic_revision", err == nil)

	pos := size - 64
	saved, err := chip.Read(pos, 40)
	check("read ok", err == nil)
	pattern := make([]byte, 40)
	for i := range pattern {
		pattern[i] = byte(i*7 + 3)
	}
	check("write ok", chip.Write(pos, pattern) == nil)
	back, err := chip.Read(pos, 40)
	check("write/read round-trip", err == nil && bytes.Equal(back, pattern))
	check("restore", chip.Write(pos, saved) == nil)
	_, err = chip.Read(size-1, 2)
	check("range rejected", err != nil)

	ok, err := chip.PresentPassword([8]byte{})
	check("present factory password", err == nil && ok)
	open, err := chip.SessionOpen()
	check("session open after present", err == nil && open)
	rf, i2cLevel, err := chip.GetAreaProtection(1)
	check("get_area_protection", err == nil && rf <= 0x0F && i2cLevel <= 3)
	_, err = chip.GetGPO()
	check("get_gpo", err == nil)
	_, err = chip.FieldPresent()
	check("field_present", err == nil)
	_, err = chip.PollInterrupt()
	check("poll_interrupt", err == nil)
	_, err = chip.MailboxStatus()
	check("mailbox_status", err == nil)
	_, err = chip.ReadNDEF()
	check("read_ndef", err == nil)
	check("wait_write_complete", chip.WaitWriteComplete() == nil)

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
