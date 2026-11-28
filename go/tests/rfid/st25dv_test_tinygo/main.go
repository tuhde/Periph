//go:build tinygo

// ST25DV hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an ST25DV on I2C1 (GP4 = SDA, GP5 = SCL;
// user area 0x53, system area 0x57). Prints PASS/FAIL per check and ends with
// the standard ===DONE: ... === line.
package main

import (
	"bytes"
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
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
	user := connection.NewI2CConnectionWithWidth(i2c, 0x53, 2, nil, nil)
	system := connection.NewI2CConnectionWithWidth(i2c, 0x57, 2, nil, nil)
	chip, err := rfid.NewST25DVFull(user, system, false)
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
}
