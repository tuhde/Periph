//go:build tinygo

// ST25DV demo example — TinyGo / Raspberry Pi Pico W.
//
// NFC tag with host mailbox: NDEF record, page-crossing write, memory areas and password, GPO events, mailbox, energy harvesting and RF state, then restores the defaults.
// The ST25DV answers on two addresses: 0x53 (user memory, dynamic registers,
// mailbox) and 0x57 (system area), both with 16-bit register addresses.
package main

import (
	"bytes"
	"fmt"
	"machine"
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
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	user := connection.NewI2CConnectionWithWidth(i2c, 0x53, 2, nil, nil)   // Create user-area connection, (i2c, addr=0x53, regBytes=2) → (*I2CConnection)
	system := connection.NewI2CConnectionWithWidth(i2c, 0x57, 2, nil, nil) // Create system-area connection, (i2c, addr=0x57, regBytes=2) → (*I2CConnection)

	chip, err := rfid.NewST25DVFull(user, system, false) // Create ST25DV driver, (user, system, gpoActiveHigh=false) → (*ST25DVFull, error)
	chk(err)

	// --- Identify the tag ---
	// IC_REF tells the 04K apart from the 16K/64K; the size comes from MEM_SIZE.
	rev, err := chip.ICRevision() // Read IC revision, () → (uint8, error)
	chk(err)
	fmt.Printf("IC_REF 0x%02X, revision %d\n", chip.ICReference(), rev) // Get IC reference, () → uint8
	uid, err := chip.UID()                                              // Read unique ID, () → ([]byte, error)
	chk(err)
	fmt.Printf("UID % X - %d bytes\n", uid, chip.MemorySize()) // Get user memory size, () → int bytes

	// --- NDEF text record a phone can read ---
	// Record header D1 (MB/ME/SR, well-known), type 'T', status 02 (language "en").
	text := []byte("Hello from Periph")
	record := append([]byte{0xD1, 0x01, byte(3 + len(text)), 0x54, 0x02, 'e', 'n'}, text...)
	chk(chip.WriteNDEF(record))  // Write NDEF message, (message) → error
	back, err := chip.ReadNDEF() // Read NDEF message, () → ([]byte, error)
	chk(err)
	fmt.Println("NDEF read back:", bytes.Equal(back, record))

	// --- Raw bytes across a 256-byte page boundary ---
	// The driver splits the write into 16-byte-aligned pieces and ACK-polls each.
	saved, err := chip.Read(0x00F8, 32) // Read user memory, (address, length) → ([]byte, error)
	chk(err)
	pattern := make([]byte, 32)
	for i := range pattern {
		pattern[i] = byte(0x40 + i)
	}
	chk(chip.Write(0x00F8, pattern))  // Write user memory, (address, data) → error
	chk(chip.WaitWriteComplete())     // Wait for the write cycle, () → error
	got, err := chip.Read(0x00F8, 32) // Read user memory, (address, length) → ([]byte, error)
	chk(err)
	fmt.Println("Page-crossing write ok:", bytes.Equal(got, pattern))
	chk(chip.Write(0x00F8, saved)) // Write user memory, (address, data) → error

	// --- Password session and two memory areas ---
	// Area 1 = first 1 KiB (stays open), area 2 = the rest: RF writes need RF
	// password 1, I2C writes need the I2C session. The factory I2C password is 0.
	var pwd [8]byte
	open, err := chip.SessionOpen() // Query I²C security session, () → (bool, error)
	chk(err)
	fmt.Println("Session before:", open)
	ok, err := chip.PresentPassword(pwd) // Present I²C password, (password [8]byte) → (bool, error)
	chk(err)
	fmt.Println("Password accepted:", ok)
	first := chip.MemorySize()
	if first > 1024 {
		first = 1024
	}
	end1 := uint8(first/32 - 1)
	last := uint8(chip.MemorySize()/32 - 1)
	chk(chip.SetAreaEnd(1, end1))                  // Set end of area 1-3, (area 1–3, endBlock 0–255) → error
	chk(chip.SetAreaEnd(2, last))                  // Set end of area 1-3, (area 1–3, endBlock 0–255) → error
	chk(chip.SetAreaEnd(3, last))                  // Set end of area 1-3, (area 1–3, endBlock 0–255) → error
	chk(chip.SetAreaProtection(2, 0x05, 0x01))     // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → error
	rf, i2cLevel, err := chip.GetAreaProtection(2) // Get area protection, (area 1–4) → (rf, i2c uint8, error)
	chk(err)
	fmt.Printf("Area 2 protection rf 0x%02X i2c %d\n", rf, i2cLevel)

	// --- Change and restore the I2C password ---
	newPwd := [8]byte{0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0}
	chk(chip.WritePassword(newPwd))        // Change I²C password, (password [8]byte) → error
	ok, err = chip.PresentPassword(newPwd) // Present I²C password, (password [8]byte) → (bool, error)
	chk(err)
	fmt.Println("New password accepted:", ok)
	chk(chip.WritePassword(pwd))        // Change I²C password, (password [8]byte) → error
	ok, err = chip.PresentPassword(pwd) // Present I²C password, (password [8]byte) → (bool, error)
	chk(err)
	fmt.Println("Factory password restored:", ok)

	// --- GPO events while a phone approaches ---
	// Field change and RF write events pulse GPO; without a GPO pin the driver
	// polls IT_STS_Dyn in a goroutine. Hold a phone to the antenna now.
	chk(chip.SetInterruptPulse(3))                                                     // Set GPO pulse duration, (value 0–7) → error
	chk(chip.SetGPO(rfid.ST25DVGPOFieldChange, true))                                  // Write static GPO register, (sources, enable) → error
	chk(chip.EnableInterrupt(rfid.ST25DVSourceFieldRising | rfid.ST25DVSourceRFWrite)) // Enable interrupt sources, (source) → error
	gpo, err := chip.GetGPO()                                                          // Read static GPO register, () → (uint8, error)
	chk(err)
	fmt.Printf("GPO register: 0x%02X\n", gpo)
	pending, err := chip.PollInterrupt() // Read and clear IT_STS_Dyn, () → (uint8, error)
	chk(err)
	fmt.Printf("Pending before: 0x%02X\n", pending)
	chk(chip.OnInterrupt(func(s uint8) { // Subscribe to GPO events, (callback) → error
		if s&rfid.ST25DVSourceFieldRising != 0 {
			fmt.Println("  GPO event: field on")
		}
		if s&rfid.ST25DVSourceFieldFalling != 0 {
			fmt.Println("  GPO event: field off")
		}
		if s&rfid.ST25DVSourceRFWrite != 0 {
			fmt.Println("  GPO event: RF write")
		}
	}))
	for i := 0; i < 10; i++ {
		field, err := chip.FieldPresent() // Query RF field, () → (bool, error)
		chk(err)
		fmt.Println("  field present:", field)
		time.Sleep(time.Second)
	}
	chk(chip.OffInterrupt())                             // Unsubscribe, () → error
	chk(chip.DisableInterrupt(rfid.ST25DVSourceRFWrite)) // Disable interrupt sources, (source) → error

	// --- Fast transfer mode: host <-> phone mailbox ---
	// The host puts a message; an RF reader (e.g. the ST25 app) reads it and can
	// answer. The watchdog releases an unread message after 2^(7-1) x 30 ms.
	chk(chip.EnableMailbox())                      // Enable fast transfer mode, () → error
	chk(chip.SetMailboxWatchdog(7))                // Set mailbox watchdog, (value 0–7) → error
	chk(chip.MailboxWrite([]byte("hello reader"))) // Put mailbox message, (data 1–256 bytes) → error
	for i := 0; i < 10; i++ {
		st, err := chip.MailboxStatus() // Decode mailbox status, () → (ST25DVMailboxStatus, error)
		chk(err)
		if st.RFPut {
			msg, err := chip.MailboxRead() // Read mailbox message, () → ([]byte, error)
			chk(err)
			fmt.Printf("Reader says: %q\n", msg)
			break
		}
		fmt.Printf("  mailbox: %+v\n", st)
		time.Sleep(time.Second)
	}
	chk(chip.DisableMailbox()) // Disable fast transfer mode, () → error

	// --- Energy harvesting and RF state ---
	chk(chip.SetEnergyHarvesting(true, false)) // Control energy harvesting, (enabled, forced) → error
	time.Sleep(time.Second)
	chk(chip.SetEnergyHarvesting(false, false)) // Control energy harvesting, (enabled, forced) → error
	chk(chip.SetRFState(false, true))           // Set RF interface state, (disable, sleep) → error
	fmt.Println("RF asleep: a phone no longer sees the tag")
	time.Sleep(2 * time.Second)
	chk(chip.SetRFState(false, false)) // Set RF interface state, (disable, sleep) → error

	// --- Restore the delivery defaults ---
	// One area, no protection, field-change GPO only, mailbox off, CC unlocked.
	chk(chip.SetAreaProtection(2, 0x00, 0x00))        // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → error
	chk(chip.SetAreaEnd(1, last))                     // Set end of area 1-3, (area 1–3, endBlock 0–255) → error
	chk(chip.SetGPO(rfid.ST25DVGPOFieldChange, true)) // Write static GPO register, (sources, enable) → error
	chk(chip.SetGPORuntime(true))                     // Switch GPO output at runtime, (enable) → error
	chk(chip.WriteSystem(0x000D, []byte{0x00}))       // Write system area, (address, data) → error
	mb, err := chip.ReadSystem(0x000D, 1)             // Read system area, (address, length) → ([]byte, error)
	chk(err)
	fmt.Println("MB_MODE:", mb[0])
	chk(chip.LockCCFile(false)) // Lock CC file blocks, (lock) → error
	fmt.Println("Done.")
}
