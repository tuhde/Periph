package rfid

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// Supports the two access patterns chip drivers in this repo use:
//   - Register-addressed reads (WriteRead([]byte{reg}, n)): backed by a
//     byte-addressable registers map. Preload it with setRegister.
//   - Plain streamed reads (Read(n), no register address): backed by a FIFO
//     queue. Preload responses with queueRead; each Read(n) call pops the
//     next one. Falls back to n zero bytes if the queue is empty.
//
// Every Write call (register writes and plain command writes alike) is
// appended to writes for assertions, and 2+ byte writes are also applied to
// registers so a later WriteRead sees them.
//
// MFRC522 additionally polls FIFOLevelReg then reads FIFODataReg one byte at
// a time, across several transceive rounds within a single call (e.g.
// ReadUID's REQA -> anticollision -> select -> halt). A plain register map
// can't model "how many bytes are left in *this* round's response" since
// that changes each round, so fifoChunks holds a queue of whole-round
// responses: queueFifo appends one, a FIFOLevelReg read reports the front
// chunk's remaining length, and each FIFODataReg read pops one byte from it
// (dropping the chunk once drained so the next queued response becomes
// visible to the next FIFOLevelReg read).
type mockConnection struct {
	registers  map[byte]byte
	writes     [][]byte
	readQueue  [][]byte
	fifoChunks [][]byte
	stickyRegs map[byte]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{registers: map[byte]byte{}, stickyRegs: map[byte]byte{}}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

// setSticky pins a register's read value regardless of any Write() the
// driver performs to it afterwards. MFRC522's Authenticate() legitimately
// clears Status2Reg (register 0x08) itself before polling it for the
// hardware-set MFCrypto1On bit; since this driver addresses I2C registers
// with the same byte for read and write (no separate read/write address
// bit the way the SPI framing has), a plain register-map mock can't
// preset "the poll eventually reads back success" without this override —
// the driver's own clearing write would otherwise stomp the preset value
// before the poll loop ever runs.
func (m *mockConnection) setSticky(reg, value byte) {
	m.stickyRegs[reg] = value
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *mockConnection) queueFifo(data []byte) {
	m.fifoChunks = append(m.fifoChunks, append([]byte(nil), data...))
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	if len(data) >= 2 {
		reg := data[0]
		for i := 1; i < len(data); i++ {
			m.registers[reg+byte(i-1)] = data[i]
		}
	}
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	if reg == mfrcRegFIFOLevel && n == 1 {
		level := 0
		if len(m.fifoChunks) > 0 {
			level = len(m.fifoChunks[0])
		}
		return []byte{byte(level)}, nil
	}
	if reg == mfrcRegFIFOData && n == 1 {
		if len(m.fifoChunks) == 0 {
			return []byte{0}, nil
		}
		b := m.fifoChunks[0][0]
		m.fifoChunks[0] = m.fifoChunks[0][1:]
		if len(m.fifoChunks[0]) == 0 {
			m.fifoChunks = m.fifoChunks[1:]
		}
		return []byte{b}, nil
	}
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		if v, ok := m.stickyRegs[reg+byte(i)]; ok {
			out[i] = v
		} else {
			out[i] = m.registers[reg+byte(i)]
		}
	}
	return out, nil
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func writesTo(writes [][]byte, reg byte) [][]byte {
	var out [][]byte
	for _, w := range writes {
		if len(w) == 2 && w[0] == reg {
			out = append(out, w)
		}
	}
	return out
}

// prepTransceive sets the constant "transceive completed" IRQ/error signal
// for every cardCommand(Transceive) round-trip on this connection (every
// call reads these fresh, and every test here wants the same outcome for
// all of a sequence's steps), then queues one FIFO response chunk.
func prepTransceive(t *testing.T, conn *mockConnection, comIrq, errReg byte, fifoBytes []byte) {
	t.Helper()
	conn.setRegister(mfrcRegComIrq, comIrq)
	conn.setRegister(mfrcRegError, errReg)
	if fifoBytes != nil {
		conn.queueFifo(fifoBytes)
	}
}

func TestMFRC522Init(t *testing.T) {
	conn := newMockConnection()
	if _, err := NewMFRC522Full(conn); err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}

	if w := writesTo(conn.writes, mfrcRegCommand); len(w) == 0 || w[0][1] != mfrcCmdSoftReset {
		t.Errorf("init: first CommandReg write = %v, want SoftReset (0x0F)", w)
	}
	if w := writesTo(conn.writes, mfrcRegTMode); len(w) == 0 || w[len(w)-1][1] != 0x80 {
		t.Errorf("init: last TModeReg write = %v, want 0x80", w)
	}
	if w := writesTo(conn.writes, mfrcRegTPrescaler); len(w) == 0 || w[len(w)-1][1] != 0xA9 {
		t.Errorf("init: last TPrescalerReg write = %v, want 0xA9", w)
	}
	if w := writesTo(conn.writes, mfrcRegTReloadH); len(w) == 0 || w[len(w)-1][1] != 0x03 {
		t.Errorf("init: last TReloadRegH write = %v, want 0x03", w)
	}
	if w := writesTo(conn.writes, mfrcRegTReloadL); len(w) == 0 || w[len(w)-1][1] != 0xE8 {
		t.Errorf("init: last TReloadRegL write = %v, want 0xE8", w)
	}
	if w := writesTo(conn.writes, mfrcRegTxASK); len(w) == 0 || w[len(w)-1][1] != 0x40 {
		t.Errorf("init: last TxASKReg write = %v, want 0x40 (Force100ASK)", w)
	}
	if w := writesTo(conn.writes, mfrcRegMode); len(w) == 0 || w[len(w)-1][1] != 0x3D {
		t.Errorf("init: last ModeReg write = %v, want 0x3D (CRC_A preset)", w)
	}
	if conn.registers[mfrcRegTxControl]&0x03 != 0x03 {
		t.Errorf("init: TxControlReg antenna bits = %#x, want 0x03 (antenna on)", conn.registers[mfrcRegTxControl])
	}
}

func TestMFRC522IsCardPresent(t *testing.T) {
	conn := newMockConnection()
	sensor, err := NewMFRC522Full(conn)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	prepTransceive(t, conn, 0x30, 0x00, []byte{0x04, 0x00})
	present, err := sensor.IsCardPresent()
	if err != nil || !present {
		t.Errorf("IsCardPresent() = %v, %v, want true, nil", present, err)
	}

	conn2 := newMockConnection()
	sensor2, err := NewMFRC522Full(conn2)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	prepTransceive(t, conn2, 0x01, 0x00, nil) // TimerIRq only -> no card
	present2, err := sensor2.IsCardPresent()
	if err != nil || present2 {
		t.Errorf("IsCardPresent() (no card) = %v, %v, want false, nil", present2, err)
	}
}

func TestMFRC522ReadUID(t *testing.T) {
	conn := newMockConnection()
	sensor, err := NewMFRC522Full(conn)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}

	uidBytes := []byte{0x12, 0x34, 0x56, 0x78}
	bcc := byte(0)
	for _, b := range uidBytes {
		bcc ^= b
	}

	conn.setRegister(mfrcRegComIrq, 0x30)
	conn.setRegister(mfrcRegError, 0x00)
	// Every calcCRC() call in this flow (inside piccSelect() and haltCard())
	// polls DivIrqReg, which defaults to 0 (never set here) - it just runs
	// its full bounded retry loop and returns a placeholder CRC, which is
	// fine: the mock's transceive success is keyed on ComIrq/Error, not on
	// the CRC bytes actually being cryptographically correct.
	conn.queueFifo([]byte{0x04, 0x00})                         // REQA response (IsCardPresent(), called first by ReadUID())
	conn.queueFifo(append(append([]byte{}, uidBytes...), bcc)) // anticollision CL1 response: 4 UID bytes + BCC
	conn.queueFifo([]byte{0x00})                               // select CL1 response: SAK, completion bit clear
	// HLTA (halt) - result ignored by the driver, no response bytes needed.

	uid, err := sensor.ReadUID()
	if err != nil || !bytes.Equal(uid, uidBytes) {
		t.Errorf("ReadUID() = %v, %v, want %v, nil", uid, err, uidBytes)
	}

	conn2 := newMockConnection()
	sensor2, err := NewMFRC522Full(conn2)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	conn2.setRegister(mfrcRegComIrq, 0x01) // TimerIRq only -> no card
	uid2, err := sensor2.ReadUID()
	if err != nil || uid2 != nil {
		t.Errorf("ReadUID() (no card) = %v, %v, want nil, nil", uid2, err)
	}
}

func TestMFRC522Antenna(t *testing.T) {
	conn := newMockConnection()
	sensor, err := NewMFRC522Full(conn)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}

	if err := sensor.AntennaOff(); err != nil {
		t.Fatalf("AntennaOff: %v", err)
	}
	if conn.registers[mfrcRegTxControl]&0x03 != 0x00 {
		t.Errorf("AntennaOff: TxControlReg antenna bits = %#x, want 0x00", conn.registers[mfrcRegTxControl])
	}
	if err := sensor.AntennaOn(); err != nil {
		t.Fatalf("AntennaOn: %v", err)
	}
	if conn.registers[mfrcRegTxControl]&0x03 != 0x03 {
		t.Errorf("AntennaOn: TxControlReg antenna bits = %#x, want 0x03", conn.registers[mfrcRegTxControl])
	}

	// Go's SetAntennaGain/AntennaGain work in raw RxGain register bits
	// (RX_GAIN_* constants), not decoded dB values — there is no dB lookup
	// table and no validation/rejection of an "invalid" raw value, unlike
	// the Python reference's dB-based API with ValueError on bad input.
	if err := sensor.SetAntennaGain(RX_GAIN_38_DB); err != nil {
		t.Fatalf("SetAntennaGain: %v", err)
	}
	if conn.registers[mfrcRegRFCfg]&0x70 != RX_GAIN_38_DB {
		t.Errorf("SetAntennaGain: RFCfgReg RxGain bits = %#x, want %#x", conn.registers[mfrcRegRFCfg]&0x70, RX_GAIN_38_DB)
	}
	conn.setRegister(mfrcRegRFCfg, RX_GAIN_43_DB)
	gain, err := sensor.AntennaGain()
	if err != nil || gain != RX_GAIN_43_DB {
		t.Errorf("AntennaGain() = %#x, %v, want %#x, nil", gain, err, RX_GAIN_43_DB)
	}
}

func TestMFRC522Version(t *testing.T) {
	conn := newMockConnection()
	sensor, err := NewMFRC522Full(conn)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	conn.setRegister(mfrcRegVersion, 0x92) // chipType=9, version=2
	chipType, version, err := sensor.Version()
	if err != nil || chipType != 9 || version != 2 {
		t.Errorf("Version() = (%d, %d), %v, want (9, 2), nil", chipType, version, err)
	}
}

func TestMFRC522SelfTest(t *testing.T) {
	conn := newMockConnection()
	sensor, err := NewMFRC522Full(conn)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	conn.setRegister(mfrcRegVersion, 0x91) // version=1 -> v1.0 reference table
	// FIFO fills to >=64 bytes on the first FIFOLevelReg poll (queued as one
	// chunk up front), then readFIFO(64) must return the exact table.
	conn.queueFifo(refV10[:])
	ok, err := sensor.SelfTest()
	if err != nil || !ok {
		t.Errorf("SelfTest() = %v, %v, want true, nil", ok, err)
	}
}

func TestMFRC522Authenticate(t *testing.T) {
	uidBytes := []byte{0x12, 0x34, 0x56, 0x78}

	conn := newMockConnection()
	sensor, err := NewMFRC522Full(conn)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	conn.setSticky(mfrcRegStatus2, 0x08) // MFCrypto1On set immediately, regardless of Authenticate()'s own clearing write
	ok, err := sensor.Authenticate(4, KEY_A, []byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}, uidBytes)
	if err != nil || !ok {
		t.Errorf("Authenticate() = %v, %v, want true, nil", ok, err)
	}

	if err := sensor.StopCrypto(); err != nil {
		t.Fatalf("StopCrypto: %v", err)
	}
	if conn.registers[mfrcRegStatus2]&0x08 != 0 {
		t.Errorf("StopCrypto: Status2Reg MFCrypto1On bit = %#x, want 0", conn.registers[mfrcRegStatus2]&0x08)
	}

	conn2 := newMockConnection()
	sensor2, err := NewMFRC522Full(conn2)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	ok2, err := sensor2.Authenticate(4, KEY_A, []byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF}, uidBytes) // 5-byte key
	if err != nil || ok2 {
		t.Errorf("Authenticate() (bad key length) = %v, %v, want false, nil", ok2, err)
	}
}

func TestMFRC522ReadWriteBlock(t *testing.T) {
	blockData := make([]byte, 16)
	for i := range blockData {
		blockData[i] = byte(i)
	}

	// calcCRC() polls DivIrqReg; make it show CRCIRq set immediately, and
	// preload CRCResultH/L with a fixed placeholder - the driver just
	// forwards whatever the chip returns as the trailing 2 command bytes,
	// so any placeholder value round-trips correctly.
	conn := newMockConnection()
	sensor, err := NewMFRC522Full(conn)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	conn.setRegister(mfrcRegDivIrq, 0x04)
	conn.setRegister(mfrcRegCRCResultH, 0xAB)
	conn.setRegister(mfrcRegCRCResultL, 0xCD)
	conn.setRegister(mfrcRegComIrq, 0x30)
	conn.setRegister(mfrcRegError, 0x00)
	conn.queueFifo(blockData)
	got, err := sensor.ReadBlock(4)
	if err != nil || !bytes.Equal(got, blockData) {
		t.Errorf("ReadBlock(4) = %v, %v, want %v, nil", got, err, blockData)
	}

	conn2 := newMockConnection()
	sensor2, err := NewMFRC522Full(conn2)
	if err != nil {
		t.Fatalf("NewMFRC522Full: %v", err)
	}
	conn2.setRegister(mfrcRegDivIrq, 0x04)
	conn2.setRegister(mfrcRegCRCResultH, 0xAB)
	conn2.setRegister(mfrcRegCRCResultL, 0xCD)
	conn2.setRegister(mfrcRegComIrq, 0x30)
	conn2.setRegister(mfrcRegError, 0x00)
	conn2.queueFifo([]byte{0x0A}) // phase 1 ACK (0x0A in low nibble)
	conn2.queueFifo([]byte{0x0A}) // phase 2 ACK
	ok, err := sensor2.WriteBlock(4, blockData)
	if err != nil || !ok {
		t.Errorf("WriteBlock(4, ...) = %v, %v, want true, nil", ok, err)
	}
}
