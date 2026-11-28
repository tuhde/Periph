//go:build linux && !tinygo

// ST25DV complete example — Linux host.
//
// Calls every Full-driver method once.
// The ST25DV answers on two addresses: 0x53 (user memory, dynamic registers,
// mailbox) and 0x57 (system area), both with 16-bit register addresses.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)

	user, err := connection.NewI2CConnectionWithWidth(bus, 0x53, 2, nil, nil) // Create user-area connection, (bus=1, addr=0x53, regBytes=2) → (*I2CConnection, error)
	chk(err)
	defer user.Close()
	system, err := connection.NewI2CConnectionWithWidth(bus, 0x57, 2, nil, nil) // Create system-area connection, (bus=1, addr=0x57, regBytes=2) → (*I2CConnection, error)
	chk(err)
	defer system.Close()

	chip, err := rfid.NewST25DVFull(user, system, false) // Create ST25DV driver, (user, system, gpoActiveHigh=false) → (*ST25DVFull, error)
	chk(err)

	fmt.Println("Size:", chip.MemorySize(), "bytes") // Get user memory size, () → int bytes
	// (MEM_SIZE + 1) × (BLK_SIZE + 1)
	fmt.Printf("IC_REF: 0x%02X\n", chip.ICReference()) // Get IC reference, () → uint8
	// 0x24 = ST25DV04K, 0x26 = ST25DV16K/64K
	uid, err := chip.UID() // Read unique ID, () → ([]byte, error)
	// most significant byte first, starts with E0 02
	chk(err)
	fmt.Printf("UID: % X\n", uid)
	rev, err := chip.ICRevision() // Read IC revision, () → (uint8, error)
	// IC_REV register 0x0020
	chk(err)
	fmt.Println("IC_REV:", rev)

	data := make([]byte, 40)
	for i := range data {
		data[i] = byte(i)
	}
	chk(chip.Write(0x0100, data)) // Write user memory, (address, data) → error
	// split into 16-byte-aligned writes, ACK-polled each
	back, err := chip.Read(0x0100, 40) // Read user memory, (address, length) → ([]byte, error)
	// 16-byte-aligned reads, retried while an RF reader holds the bus
	chk(err)
	fmt.Printf("Data: % X\n", back)
	chk(chip.WaitWriteComplete()) // Wait for the write cycle, () → error
	// ACK polling on 0x53

	it, err := chip.ReadSystem(0x0001, 1) // Read system area, (address, length) → ([]byte, error)
	// raw access to the static registers at 0x57
	chk(err)
	fmt.Println("IT_TIME:", it[0])
	open, err := chip.SessionOpen() // Query I²C security session, () → (bool, error)
	// I2C_SSO_Dyn bit 0
	chk(err)
	fmt.Println("Session:", open)
	ok, err := chip.PresentPassword([8]byte{}) // Present I²C password, (password [8]byte) → (bool, error)
	// opens the session needed for system-area writes
	chk(err)
	fmt.Println("Password OK:", ok)
	chk(chip.WritePassword([8]byte{})) // Change I²C password, (password [8]byte) → error
	// here rewritten with the factory value
	chk(chip.WriteSystem(0x0001, []byte{0x03})) // Write system area, (address, data) → error
	// IT_TIME back to its reset value, ACK-polled

	chk(chip.SetAreaEnd(1, uint8(chip.MemorySize()/32-1))) // Set end of area 1-3, (area 1–3, endBlock 0–255) → error
	// area n ends at byte endBlock × 32 + 31
	chk(chip.SetAreaProtection(1, 0x00, 0x00)) // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → error
	// RFAnSS value and the area's I2CSS bits
	rf, i2cLevel, err := chip.GetAreaProtection(1) // Get area protection, (area 1–4) → (rf, i2c uint8, error)
	// RFAnSS & 0x0F and the I2CSS level
	chk(err)
	fmt.Println("Area 1 protection:", rf, i2cLevel)

	chk(chip.SetGPO(rfid.ST25DVGPOFieldChange, true)) // Write static GPO register, (sources, enable) → error
	// the chip copies it into GPO_CTRL_Dyn
	gpo, err := chip.GetGPO() // Read static GPO register, () → (uint8, error)
	// ST25DVGPO* bit mask
	chk(err)
	fmt.Printf("GPO: 0x%02X\n", gpo)
	chk(chip.SetGPORuntime(true)) // Switch GPO output at runtime, (enable) → error
	// GPO_CTRL_Dyn.GPO_EN, no session needed
	chk(chip.SetInterruptPulse(3)) // Set GPO pulse duration, (value 0–7) → error
	// 301 µs − value × 37.65 µs

	field, err := chip.FieldPresent() // Query RF field, () → (bool, error)
	// EH_CTRL_Dyn.FIELD_ON
	chk(err)
	fmt.Println("Field present:", field)
	chk(chip.SetEnergyHarvesting(false, false)) // Control energy harvesting, (enabled, forced) → error
	// EH_EN now, EH_MODE written only when it changes
	chk(chip.SetRFState(false, false)) // Set RF interface state, (disable, sleep) → error
	// runtime RF_MNGT_Dyn

	chk(chip.EnableMailbox()) // Enable fast transfer mode, () → error
	// sets MB_MODE if needed, then MB_CTRL_Dyn.MB_EN
	chk(chip.SetMailboxWatchdog(0)) // Set mailbox watchdog, (value 0–7) → error
	// 2^(value−1) × 30 ms, 0 disables it
	chk(chip.MailboxWrite([]byte("ping"))) // Put mailbox message, (data 1–256 bytes) → error
	// single write starting at 0x2008
	st, err := chip.MailboxStatus() // Decode mailbox status, () → (ST25DVMailboxStatus, error)
	// Enabled, HostPut, RFPut, HostMiss, RFMiss, …
	chk(err)
	fmt.Printf("Mailbox: %+v\n", st)
	msg, err := chip.MailboxRead() // Read mailbox message, () → ([]byte, error)
	// MB_LEN_Dyn + 1 bytes, empty if no message
	chk(err)
	fmt.Printf("Message: %q\n", msg)
	chk(chip.DisableMailbox()) // Disable fast transfer mode, () → error
	// clears MB_EN, which also drops the message

	chk(chip.WriteNDEF([]byte{0xD1, 0x01, 0x05, 0x54, 0x02, 0x65, 0x6E, 0x48, 0x69, 0x21})) // Write NDEF message, (message) → error
	// capability container + TLV 03 <len> … FE
	ndef, err := chip.ReadNDEF() // Read NDEF message, () → ([]byte, error)
	// parses the CC and the first NDEF TLV
	chk(err)
	fmt.Printf("NDEF: % X\n", ndef)
	chk(chip.LockCCFile(false)) // Lock CC file blocks, (lock) → error
	// false keeps blocks 0/1 RF-writable

	chk(chip.EnableInterrupt(rfid.ST25DVSourceFieldRising | rfid.ST25DVSourceRFWrite)) // Enable interrupt sources, (source) → error
	// static GPO register bits plus GPO_EN
	chk(chip.OnInterrupt(func(s uint8) { fmt.Printf("GPO event: 0x%02X\n", s) })) // Subscribe to GPO events, (callback) → error
	// 5 ms IT_STS_Dyn polling goroutine without a GPO pin
	time.Sleep(100 * time.Millisecond)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	// stops delivery and the polling goroutine
	pending, err := chip.PollInterrupt() // Read and clear IT_STS_Dyn, () → (uint8, error)
	// ST25DVSource* mask, 0 = none
	chk(err)
	fmt.Printf("Pending: 0x%02X\n", pending)
	chk(chip.DisableInterrupt(rfid.ST25DVSourceRFWrite)) // Disable interrupt sources, (source) → error
	// clears the matching GPO enable bits
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
