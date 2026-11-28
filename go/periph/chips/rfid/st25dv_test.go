package rfid

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// st25dvMock is an in-memory RegisterConnection with 2-byte register
// addresses, recording every write/read frame.
type st25dvMock struct {
	registers map[uint32]byte
	writes    [][]byte
	nack      int // remaining write phases to reject (NACK)
	nackAfter int // successful write phases before NACKs start
	intPin    connection.InputPin
}

func newST25DVMock() *st25dvMock { return &st25dvMock{registers: map[uint32]byte{}} }

func (m *st25dvMock) set(reg uint32, values ...byte) {
	for i, v := range values {
		m.registers[reg+uint32(i)] = v
	}
}

func (m *st25dvMock) fail() error {
	if m.nackAfter > 0 {
		m.nackAfter--
	} else if m.nack > 0 {
		m.nack--
		return errors.New("NACK")
	}
	return nil
}

func (m *st25dvMock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if err := m.fail(); err != nil {
		return err
	}
	if len(data) > 2 {
		a := uint32(data[0])<<8 | uint32(data[1])
		for i, v := range data[2:] {
			m.registers[a+uint32(i)] = v
		}
	}
	return nil
}

func (m *st25dvMock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *st25dvMock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if err := m.fail(); err != nil {
		return nil, err
	}
	a := uint32(data[0])<<8 | uint32(data[1])
	out := make([]byte, n)
	for i := range out {
		out[i] = m.registers[a+uint32(i)]
	}
	return out, nil
}

func (m *st25dvMock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg >> 8), byte(reg)}, length)
}

func (m *st25dvMock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg >> 8), byte(reg)}, data...))
}

func (m *st25dvMock) Close() error                { return nil }
func (m *st25dvMock) Enable()                     {}
func (m *st25dvMock) Disable()                    {}
func (m *st25dvMock) IsEnabled() bool             { return true }
func (m *st25dvMock) IntPin() connection.InputPin { return m.intPin }
func (m *st25dvMock) EnPin() connection.OutputPin { return nil }

func newST25DVMocks(icRef, memLo, memHi byte) (*st25dvMock, *st25dvMock) {
	user, system := newST25DVMock(), newST25DVMock()
	system.set(0x0014, memLo, memHi, 0x03, icRef)
	system.set(0x0018, 0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0)
	system.set(0x0020, 0x12)
	system.set(0x0000, 0x88)
	system.set(0x0002, 0x01)
	return user, system
}

func newST25DVTag(t *testing.T) (*st25dvMock, *st25dvMock, *ST25DVFull) {
	t.Helper()
	user, system := newST25DVMocks(0x26, 0xFF, 0x01)
	tag, err := NewST25DVFull(user, system, false)
	if err != nil {
		t.Fatal(err)
	}
	return user, system, tag
}

type fakeST25DVPin struct {
	trigger connection.Trigger
	handler func()
}

func (p *fakeST25DVPin) OnEdge(trigger connection.Trigger, handler func()) func() {
	p.trigger, p.handler = trigger, handler
	return func() { p.handler = nil }
}
func (p *fakeST25DVPin) IsHigh() (bool, error) { return true, nil }
func (p *fakeST25DVPin) Close() error          { return nil }

func TestST25DVMinimalReadWrite(t *testing.T) {
	user, system := newST25DVMocks(0x26, 0xFF, 0x01)
	tag, err := NewST25DVMinimal(user, system, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(system.writes) != 1 || !bytes.Equal(system.writes[0], []byte{0x00, 0x14}) || len(user.writes) != 0 {
		t.Errorf("init traffic: sys=%v user=%v", system.writes, user.writes)
	}
	if tag.size != 2048 {
		t.Errorf("size = %d", tag.size)
	}
	user.set(0x0100, 1, 2, 3)
	if b, err := tag.Read(0x0100, 3); err != nil || !bytes.Equal(b, []byte{1, 2, 3}) {
		t.Errorf("Read = %v, %v", b, err)
	}
	if _, err := tag.Read(2047, 2); err != ErrST25DVRange {
		t.Errorf("Read range err = %v", err)
	}
	data := make([]byte, 40)
	for i := range data {
		data[i] = byte(i)
	}
	user.writes = nil
	if err := tag.Write(0x0008, data); err != nil {
		t.Fatal(err)
	}
	var frames [][]byte
	polls := 0
	for _, w := range user.writes {
		if len(w) > 2 {
			frames = append(frames, w)
		} else {
			polls++
		}
	}
	want := [][]byte{
		append([]byte{0x00, 0x08}, data[:8]...),
		append([]byte{0x00, 0x10}, data[8:24]...),
		append([]byte{0x00, 0x20}, data[24:]...),
	}
	if len(frames) != 3 || polls != 3 {
		t.Fatalf("frames=%d polls=%d", len(frames), polls)
	}
	for i := range want {
		if !bytes.Equal(frames[i], want[i]) {
			t.Errorf("frame %d = %v", i, frames[i])
		}
	}
	if err := tag.Write(2040, make([]byte, 9)); err != ErrST25DVRange {
		t.Errorf("Write range err = %v", err)
	}
	user.nackAfter, user.nack = 1, 1000
	if err := tag.Write(0, []byte{1}); err != ErrST25DVTimeout {
		t.Errorf("Write timeout err = %v", err)
	}
	user.nackAfter, user.nack = 0, 0

	// Read retry: two NACKs, then success; three NACKs fail.
	user.set(0x0040, 0x77)
	user.nack = 2
	if b, err := tag.Read(0x0040, 1); err != nil || b[0] != 0x77 {
		t.Errorf("Read retry = %v, %v", b, err)
	}
	user.nack = 3
	if _, err := tag.Read(0x0040, 1); err == nil {
		t.Errorf("Read after 3 NACKs succeeded")
	}
	user.nack = 0
}

func TestST25DVIdentificationAndPassword(t *testing.T) {
	for _, c := range []struct {
		ic, lo, hi byte
		size       int
	}{{0x24, 0x7F, 0x00, 512}, {0x26, 0xFF, 0x01, 2048}, {0x26, 0xFF, 0x07, 8192}} {
		u, s := newST25DVMocks(c.ic, c.lo, c.hi)
		tag, err := NewST25DVFull(u, s, false)
		if err != nil || tag.MemorySize() != c.size || tag.ICReference() != c.ic {
			t.Errorf("size %d: got %d / 0x%02X, %v", c.size, tag.MemorySize(), tag.ICReference(), err)
		}
	}
	user, system, tag := newST25DVTag(t)
	if uid, err := tag.UID(); err != nil || !bytes.Equal(uid, []byte{0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11}) {
		t.Errorf("UID = %X, %v", uid, err)
	}
	if v, err := tag.ICRevision(); err != nil || v != 0x12 {
		t.Errorf("ICRevision = %v, %v", v, err)
	}
	if b, err := tag.ReadSystem(0x0017, 1); err != nil || b[0] != 0x26 {
		t.Errorf("ReadSystem = %v, %v", b, err)
	}
	system.writes = nil
	if err := tag.WriteSystem(0x0001, []byte{5}); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(system.writes[0], []byte{0x00, 0x01, 0x05}) || !bytes.Equal(system.writes[1], []byte{0x00, 0x00}) {
		t.Errorf("WriteSystem frames = %v", system.writes)
	}

	pw := [8]byte{1, 2, 3, 4, 5, 6, 7, 8}
	frame := append(append(append([]byte{0x09, 0x00}, pw[:]...), 0x09), pw[:]...)
	user.set(0x2004, 0x01)
	system.writes = nil
	if ok, err := tag.PresentPassword(pw); err != nil || !ok {
		t.Errorf("PresentPassword = %v, %v", ok, err)
	}
	if !bytes.Equal(system.writes[0], frame) {
		t.Errorf("present frame = %X", system.writes[0])
	}
	user.set(0x2004, 0x00)
	if ok, _ := tag.PresentPassword(pw); ok {
		t.Errorf("PresentPassword with closed session = true")
	}
	if err := tag.WritePassword(pw); err != ErrST25DVSessionClosed {
		t.Errorf("WritePassword closed err = %v", err)
	}
	user.set(0x2004, 0x01)
	system.writes = nil
	if err := tag.WritePassword(pw); err != nil {
		t.Fatal(err)
	}
	frame[10] = 0x07
	if !bytes.Equal(system.writes[0], frame) {
		t.Errorf("write-password frame = %X", system.writes[0])
	}
}

func TestST25DVConfiguration(t *testing.T) {
	user, system, tag := newST25DVTag(t)
	system.writes = nil
	if err := tag.SetAreaEnd(2, 0x1F); err != nil || !bytes.Equal(system.writes[0], []byte{0x00, 0x07, 0x1F}) {
		t.Errorf("SetAreaEnd = %v %v", err, system.writes)
	}
	if tag.SetAreaEnd(4, 1) != ErrST25DVArgument || tag.SetAreaProtection(5, 0, 0) != ErrST25DVArgument ||
		tag.SetAreaProtection(1, 0, 4) != ErrST25DVArgument {
		t.Errorf("argument checks failed")
	}
	system.set(0x000B, 0x00)
	_ = tag.SetAreaProtection(3, 0x05, 0x03)
	_ = tag.SetAreaProtection(2, 0x0C, 0x01)
	if system.registers[0x0008] != 0x05 || system.registers[0x000B] != 0x34 {
		t.Errorf("area protection regs = 0x%02X 0x%02X", system.registers[0x0008], system.registers[0x000B])
	}
	if rf, i2c, err := tag.GetAreaProtection(3); err != nil || rf != 0x05 || i2c != 0x03 {
		t.Errorf("GetAreaProtection(3) = %v %v %v", rf, i2c, err)
	}

	_ = tag.SetGPO(ST25DVGPOFieldChange|ST25DVGPORFWrite, true)
	if system.registers[0x0000] != 0xC8 {
		t.Errorf("SetGPO = 0x%02X", system.registers[0x0000])
	}
	_ = tag.SetGPO(ST25DVGPOFieldChange, false)
	if v, _ := tag.GetGPO(); v != 0x08 {
		t.Errorf("GetGPO = 0x%02X", v)
	}
	_ = tag.SetGPORuntime(true)
	if user.registers[0x2000] != 0x80 {
		t.Errorf("SetGPORuntime = 0x%02X", user.registers[0x2000])
	}
	_ = tag.SetInterruptPulse(3)
	if system.registers[0x0001] != 3 || tag.SetInterruptPulse(8) != ErrST25DVArgument {
		t.Errorf("SetInterruptPulse")
	}

	user.set(0x2002, 0x0C)
	if ok, _ := tag.FieldPresent(); !ok {
		t.Errorf("FieldPresent = false")
	}
	system.set(0x0002, 0x01)
	system.writes = nil
	_ = tag.SetEnergyHarvesting(true, false)
	if len(system.writes) != 1 || user.registers[0x2002] != 0x01 {
		t.Errorf("SetEnergyHarvesting on: %v 0x%02X", system.writes, user.registers[0x2002])
	}
	_ = tag.SetEnergyHarvesting(false, true)
	if system.registers[0x0002] != 0x00 || user.registers[0x2002] != 0x00 {
		t.Errorf("SetEnergyHarvesting forced")
	}
	_ = tag.SetRFState(false, true)
	if user.registers[0x2003] != 0x02 {
		t.Errorf("SetRFState sleep = 0x%02X", user.registers[0x2003])
	}
	_ = tag.SetRFState(true, false)
	if user.registers[0x2003] != 0x01 {
		t.Errorf("SetRFState disable = 0x%02X", user.registers[0x2003])
	}

	_ = tag.LockCCFile(true)
	if system.registers[0x000C] != 0x03 {
		t.Errorf("LockCCFile")
	}
	_ = tag.SetMailboxWatchdog(4)
	if system.registers[0x000E] != 4 || tag.SetMailboxWatchdog(8) != ErrST25DVArgument {
		t.Errorf("SetMailboxWatchdog")
	}
}

func TestST25DVMailbox(t *testing.T) {
	user, system, tag := newST25DVTag(t)
	system.set(0x000D, 0x00)
	if err := tag.EnableMailbox(); err != nil || system.registers[0x000D] != 0x01 || user.registers[0x2006] != 0x01 {
		t.Errorf("EnableMailbox: %v", err)
	}
	user.set(0x2006, 0x01|0x04|0x20)
	st, _ := tag.MailboxStatus()
	if !st.Enabled || !st.RFPut || st.HostPut || !st.RFMiss {
		t.Errorf("MailboxStatus = %+v", st)
	}
	if tag.MailboxWrite([]byte{1}) != ErrST25DVMailboxBusy {
		t.Errorf("busy not detected")
	}
	user.set(0x2006, 0x01)
	msg := make([]byte, 100)
	for i := range msg {
		msg[i] = byte(i)
	}
	user.writes = nil
	if err := tag.MailboxWrite(msg); err != nil || !bytes.Equal(user.writes[len(user.writes)-1], append([]byte{0x20, 0x08}, msg...)) {
		t.Errorf("MailboxWrite frame: %v", err)
	}
	if tag.MailboxWrite(nil) != ErrST25DVArgument || tag.MailboxWrite(make([]byte, 257)) != ErrST25DVArgument {
		t.Errorf("MailboxWrite length checks")
	}
	user.set(0x2006, 0x00)
	if tag.MailboxWrite([]byte{1}) != ErrST25DVMailboxDisabled {
		t.Errorf("disabled not detected")
	}
	if b, _ := tag.MailboxRead(); len(b) != 0 {
		t.Errorf("MailboxRead empty = %v", b)
	}
	user.set(0x2006, 0x05)
	user.set(0x2007, 19)
	want := make([]byte, 20)
	for i := range want {
		want[i] = byte(0xA0 + i)
		user.registers[uint32(0x2008+i)] = want[i]
	}
	if b, err := tag.MailboxRead(); err != nil || !bytes.Equal(b, want) {
		t.Errorf("MailboxRead = %X, %v", b, err)
	}
	_ = tag.DisableMailbox()
	if user.registers[0x2006] != 0 {
		t.Errorf("DisableMailbox")
	}
}

func TestST25DVNDEF(t *testing.T) {
	user, _, tag := newST25DVTag(t)
	ndef := []byte{0xD1, 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x48}
	if err := tag.WriteNDEF(ndef); err != nil {
		t.Fatal(err)
	}
	head := []byte{user.registers[0], user.registers[1], user.registers[2], user.registers[3], user.registers[4], user.registers[5]}
	if !bytes.Equal(head, []byte{0xE1, 0x40, 0xFF, 0x01, 0x03, byte(len(ndef))}) || user.registers[uint32(6+len(ndef))] != 0xFE {
		t.Errorf("NDEF image head = %X", head)
	}
	if b, err := tag.ReadNDEF(); err != nil || !bytes.Equal(b, ndef) {
		t.Errorf("ReadNDEF = %X, %v", b, err)
	}
	long := make([]byte, 300)
	_ = tag.WriteNDEF(long)
	if user.registers[5] != 0xFF || user.registers[6] != 0x01 || user.registers[7] != 0x2C {
		t.Errorf("long TLV header wrong")
	}
	if b, _ := tag.ReadNDEF(); !bytes.Equal(b, long) {
		t.Errorf("ReadNDEF long len = %d", len(b))
	}
	if tag.WriteNDEF(make([]byte, 2048)) != ErrST25DVRange {
		t.Errorf("too-large NDEF accepted")
	}
	user.set(0x0000, 0x00)
	if b, _ := tag.ReadNDEF(); len(b) != 0 {
		t.Errorf("ReadNDEF without CC = %X", b)
	}

	u4, s4 := newST25DVMocks(0x24, 0x7F, 0x00)
	t4, _ := NewST25DVFull(u4, s4, false)
	_ = t4.WriteNDEF([]byte{1})
	if !bytes.Equal([]byte{u4.registers[0], u4.registers[1], u4.registers[2], u4.registers[3]}, []byte{0xE1, 0x40, 0x40, 0x01}) {
		t.Errorf("04K CC wrong")
	}
	u64, s64 := newST25DVMocks(0x26, 0xFF, 0x07)
	t64, _ := NewST25DVFull(u64, s64, false)
	_ = t64.WriteNDEF([]byte{1, 2})
	cc := make([]byte, 8)
	for i := range cc {
		cc[i] = u64.registers[uint32(i)]
	}
	if !bytes.Equal(cc, []byte{0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, 0x03, 0xFF}) {
		t.Errorf("64K CC = %X", cc)
	}
	if b, _ := t64.ReadNDEF(); !bytes.Equal(b, []byte{1, 2}) {
		t.Errorf("64K ReadNDEF = %X", b)
	}

	user.nack = 2
	if err := tag.WaitWriteComplete(); err != nil {
		t.Errorf("WaitWriteComplete after NACKs = %v", err)
	}
	user.nack = 1000
	if err := tag.WaitWriteComplete(); err != ErrST25DVTimeout {
		t.Errorf("WaitWriteComplete timeout = %v", err)
	}
	user.nack = 0
}

func TestST25DVInterrupts(t *testing.T) {
	user, system, tag := newST25DVTag(t)
	system.set(0x0000, 0x80)
	_ = tag.EnableInterrupt(ST25DVSourceFieldRising | ST25DVSourceRFWrite)
	if system.registers[0x0000] != 0xC8 || user.registers[0x2000] != 0x80 {
		t.Errorf("EnableInterrupt GPO = 0x%02X", system.registers[0x0000])
	}
	_ = tag.EnableInterrupt(ST25DVSourceRFPutMsg | ST25DVSourceRFUser)
	if system.registers[0x0000] != 0xD9 {
		t.Errorf("EnableInterrupt mapping = 0x%02X", system.registers[0x0000])
	}
	_ = tag.DisableInterrupt(ST25DVSourceRFWrite)
	if system.registers[0x0000] != 0x99 {
		t.Errorf("DisableInterrupt = 0x%02X", system.registers[0x0000])
	}
	user.set(0x2005, 0x90)
	if v, _ := tag.PollInterrupt(); v != 0x90 {
		t.Errorf("PollInterrupt = 0x%02X", v)
	}

	pin := &fakeST25DVPin{}
	user.intPin = pin
	var got []uint8
	_ = tag.OnInterrupt(func(s uint8) { got = append(got, s) })
	if pin.trigger != connection.Falling {
		t.Errorf("trigger = %v", pin.trigger)
	}
	user.set(0x2005, 0x10)
	pin.handler()
	user.set(0x2005, 0x00)
	pin.handler()
	if len(got) != 1 || got[0] != 0x10 {
		t.Errorf("callback got %v", got)
	}
	_ = tag.OffInterrupt()
	if pin.handler != nil {
		t.Errorf("OffInterrupt did not unsubscribe")
	}

	uh, sh := newST25DVMocks(0x26, 0xFF, 0x01)
	ph := &fakeST25DVPin{}
	uh.intPin = ph
	th, _ := NewST25DVFull(uh, sh, true)
	_ = th.OnInterrupt(func(uint8) {})
	if ph.trigger != connection.Rising {
		t.Errorf("-JF trigger = %v", ph.trigger)
	}
	_ = th.OffInterrupt()

	user.intPin = nil
	_ = tag.OnInterrupt(func(uint8) {})
	if tag.pollPin == nil {
		t.Errorf("polling fallback not started")
	}
	_ = tag.OffInterrupt()
}
