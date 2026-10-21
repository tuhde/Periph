package memory

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/storage"
)

// sdModel is a behavioural SD card in SPI mode implementing
// connection.SPIExtensions: it needs 80 idle clocks before CMD0, checks the
// command CRC7, models R1/R3/R7 responses, CSD/CID, single and multi-block
// transfers with tokens, busy and CRC16.
type sdModel struct {
	connection.Connection // unused methods (Close, Enable, ...) — never called

	v2, sdhc       bool
	csizeV2        uint32
	acmd41Calls    int
	acmd41Needed   int
	gotIdleClocks  bool
	selected       bool
	inIdle         bool
	crcOn          bool
	corruptReadCRC bool
	speed          uint32
	mem            []byte
	cmdLog         []byte
	csTogglesInCmd int
	state          int // 0 CMD, 1 WTOKEN, 2 WDATA
	cmdBuf         []byte
	out            []byte
	appNext        bool
	streaming      bool
	streamBlock    uint32
	wBlock         uint32
	multiWrite     bool
	wData          []byte
}

func newSDModel(v2, sdhc bool) *sdModel {
	return &sdModel{v2: v2, sdhc: sdhc, csizeV2: 7, acmd41Needed: 3, mem: make([]byte, 8192*512)}
}

func (m *sdModel) pushBlock(data []byte) {
	m.out = append(m.out, 0xFE)
	m.out = append(m.out, data...)
	c := SDCRC16(data)
	if m.corruptReadCRC {
		c ^= 1
	}
	m.out = append(m.out, byte(c>>8), byte(c))
}

func (m *sdModel) blockOf(arg uint32) uint32 {
	if m.sdhc {
		return arg
	}
	return arg / 512
}

func (m *sdModel) doCommand() {
	wasApp := m.appNext
	m.appNext = false
	cb := m.cmdBuf
	cmd := cb[0] & 0x3F
	arg := uint32(cb[1])<<24 | uint32(cb[2])<<16 | uint32(cb[3])<<8 | uint32(cb[4])
	if wasApp {
		m.cmdLog = append(m.cmdLog, 0x80|cmd)
	} else {
		m.cmdLog = append(m.cmdLog, cmd)
	}
	if cb[5]>>1 != SDCRC7(cb[:5]) {
		m.out = append(m.out, 0xFF, 0x09)
		return
	}
	m.out = append(m.out, 0xFF) // Ncr gap
	idle := byte(0)
	if m.inIdle {
		idle = 1
	}
	if wasApp {
		if cmd == 41 {
			m.acmd41Calls++
			if m.acmd41Calls >= m.acmd41Needed {
				m.inIdle = false
				m.out = append(m.out, 0x00)
			} else {
				m.out = append(m.out, 0x01)
			}
		} else {
			m.out = append(m.out, idle)
		}
		return
	}
	switch cmd {
	case 0:
		if !m.gotIdleClocks {
			m.out = nil
			return
		}
		m.inIdle = true
		m.out = append(m.out, 0x01)
	case 8:
		if !m.v2 {
			m.out = append(m.out, 0x05)
		} else {
			m.out = append(m.out, 0x01, 0, 0, 0x01, 0xAA)
		}
	case 9:
		csd := make([]byte, 16)
		if m.sdhc {
			csd[0] = 0x40
			csd[7] = byte(m.csizeV2>>16) & 0x3F
			csd[8] = byte(m.csizeV2 >> 8)
			csd[9] = byte(m.csizeV2)
		} else {
			csd[5], csd[6], csd[7], csd[8], csd[9], csd[10] = 9, 0x03, 0xFF, 0xC0, 0x03, 0x80
		}
		m.out = append(m.out, 0x00, 0xFF)
		m.pushBlock(csd)
	case 10:
		cid := []byte{0x03, 'S', 'D', 'T', 'E', 'S', 'T', '1', 0x10, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x23, 0x01}
		m.out = append(m.out, 0x00, 0xFF)
		m.pushBlock(cid)
	case 12:
		m.streaming = false
		m.out = []byte{0xFF, 0x00, 0x00, 0x00, 0xFF}
	case 13:
		m.out = append(m.out, 0x00, 0x00)
	case 16:
		m.out = append(m.out, idle)
	case 17:
		m.out = append(m.out, 0x00, 0xFF)
		b := int(m.blockOf(arg))
		m.pushBlock(m.mem[b*512 : (b+1)*512])
	case 18:
		m.out = append(m.out, 0x00)
		m.streaming = true
		m.streamBlock = m.blockOf(arg)
	case 24, 25:
		m.out = append(m.out, 0x00)
		m.state = 1
		m.wBlock = m.blockOf(arg)
		m.multiWrite = cmd == 25
	case 32, 33:
		m.out = append(m.out, 0x00)
	case 38:
		m.out = append(m.out, 0x00, 0x00, 0x00)
	case 55:
		m.appNext = true
		m.out = append(m.out, idle)
	case 58:
		b := byte(0x80)
		if m.sdhc {
			b = 0xC0
		}
		m.out = append(m.out, idle, b, 0xFF, 0x80, 0x00)
	case 59:
		m.crcOn = arg&1 != 0
		m.out = append(m.out, idle)
	default:
		m.out = append(m.out, 0x04) // illegal command
	}
}

func (m *sdModel) feed(b byte) byte {
	switch m.state {
	case 1:
		if b == 0xFE || b == 0xFC {
			m.state = 2
			m.wData = m.wData[:0]
		} else if b == 0xFD {
			m.state = 0
			m.out = append(m.out, 0xFF, 0x00, 0x00, 0xFF)
			return 0xFF
		}
	case 2:
		m.wData = append(m.wData, b)
		if len(m.wData) == 514 {
			got := uint16(m.wData[512])<<8 | uint16(m.wData[513])
			ok := !m.crcOn || got == SDCRC16(m.wData[:512])
			if ok {
				copy(m.mem[int(m.wBlock)*512:], m.wData[:512])
				m.wBlock++
			}
			resp := byte(0xE5)
			if !ok {
				resp = 0xEB
			}
			m.out = append(m.out, resp, 0x00, 0x00, 0xFF)
			if m.multiWrite {
				m.state = 1
			} else {
				m.state = 0
			}
			return 0xFF // the data response follows on the NEXT byte clock
		}
	default:
		if len(m.cmdBuf) > 0 || b&0xC0 == 0x40 {
			m.cmdBuf = append(m.cmdBuf, b)
			if len(m.cmdBuf) == 6 {
				m.doCommand()
				m.cmdBuf = m.cmdBuf[:0]
			}
			return 0xFF
		}
	}
	if len(m.out) > 0 {
		v := m.out[0]
		m.out = m.out[1:]
		return v
	}
	if m.streaming && m.state == 0 {
		s := int(m.streamBlock) * 512
		m.pushBlock(m.mem[s : s+512])
		m.streamBlock++
		v := m.out[0]
		m.out = m.out[1:]
		return v
	}
	return 0xFF
}

func (m *sdModel) Write(data []byte) error {
	for _, b := range data {
		m.feed(b)
	}
	return nil
}

func (m *sdModel) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	for i := range out {
		out[i] = m.feed(0xFF)
	}
	return out, nil
}

func (m *sdModel) ReadInto(buf []byte) error {
	for i := range buf {
		buf[i] = m.feed(0xFF)
	}
	return nil
}

func (m *sdModel) IdleClocks(n int) error {
	if !m.selected && n >= 80 {
		m.gotIdleClocks = true
	}
	return nil
}

func (m *sdModel) SetSpeed(hz uint32) error { m.speed = hz; return nil }
func (m *sdModel) Select() error            { m.selected = true; return nil }
func (m *sdModel) Deselect() error {
	m.selected = false
	if m.state == 0 && len(m.cmdBuf) > 0 {
		m.csTogglesInCmd++
	}
	return nil
}

func TestSDCRCHelpers(t *testing.T) {
	if SDCRC16([]byte("123456789")) != 0x31C3 {
		t.Fatal("CRC16 check value")
	}
	if SDCRC7([]byte{0x40, 0, 0, 0, 0})<<1|1 != 0x95 {
		t.Fatal("CMD0 frame CRC")
	}
}

func TestSDInitV2SDHC(t *testing.T) {
	m := newSDModel(true, true)
	sd, err := NewSDSPIMinimal(m, SDSPIOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if sd.CardType() != SDCardSDHC || sd.BlockCount() != 8192 {
		t.Fatalf("type=%v blocks=%d", sd.CardType(), sd.BlockCount())
	}
	if !m.gotIdleClocks || m.speed != 12_000_000 || m.csTogglesInCmd != 0 {
		t.Fatalf("idle=%v speed=%d toggles=%d", m.gotIdleClocks, m.speed, m.csTogglesInCmd)
	}
}

func TestSDInitV1ByteAddressed(t *testing.T) {
	m := newSDModel(false, false)
	sd, err := NewSDSPIMinimal(m, SDSPIOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if sd.CardType() != SDCardV1 || sd.BlockCount() != 2097152 {
		t.Fatalf("type=%v blocks=%d", sd.CardType(), sd.BlockCount())
	}
	if !bytes.Contains(m.cmdLog, []byte{16}) {
		t.Fatal("CMD16 must be sent for byte-addressed cards")
	}
}

type deadCard struct{ connection.Connection }

func (deadCard) Write([]byte) error { return nil }
func (deadCard) ReadInto(b []byte) error {
	for i := range b {
		b[i] = 0xFF
	}
	return nil
}
func (deadCard) IdleClocks(int) error  { return nil }
func (deadCard) SetSpeed(uint32) error { return nil }
func (deadCard) Select() error         { return nil }
func (deadCard) Deselect() error       { return nil }

func TestSDNoCardIsNotReady(t *testing.T) {
	if _, err := NewSDSPIMinimal(deadCard{}, SDSPIOptions{}); !errors.Is(err, storage.ErrNotReady) {
		t.Fatalf("want ErrNotReady, got %v", err)
	}
}

func TestSDMinimalBlockRoundtrip(t *testing.T) {
	sd, err := NewSDSPIMinimal(newSDModel(true, true), SDSPIOptions{})
	if err != nil {
		t.Fatal(err)
	}
	w := make([]byte, 512)
	for i := range w {
		w[i] = byte(i*7 + 3)
	}
	if err := sd.WriteBlock(5, w); err != nil {
		t.Fatal(err)
	}
	r := make([]byte, 512)
	if err := sd.ReadBlock(5, r); err != nil || !bytes.Equal(w, r) {
		t.Fatalf("roundtrip: %v", err)
	}
	if err := sd.ReadBlock(8192, r); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatalf("want ErrOutOfRange, got %v", err)
	}
}

func TestSDFullMultiBlock(t *testing.T) {
	sd, err := NewSDSPIFull(newSDModel(true, true), SDSPIOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if sd.BlockSize() != 512 || sd.EraseSize() != 512 || sd.NeedsErase() {
		t.Fatal("geometry")
	}
	if sd.Info().Name != "TEST1" {
		t.Fatalf("name %q", sd.Info().Name)
	}
	w := make([]byte, 4*512)
	for k := range w {
		w[k] = byte(k ^ (k >> 8))
	}
	if err := sd.Write(100, w); err != nil {
		t.Fatal(err)
	}
	if err := sd.Sync(); err != nil {
		t.Fatal(err)
	}
	r := make([]byte, 4*512)
	if err := sd.Read(100, r); err != nil || !bytes.Equal(w, r) {
		t.Fatalf("multi roundtrip: %v", err)
	}
	if err := sd.Erase(100, 4); err != nil {
		t.Fatal(err)
	}
	if err := sd.Write(8190, w); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatalf("want ErrOutOfRange, got %v", err)
	}
	if ocr, err := sd.OCR(); err != nil || ocr&0x40000000 == 0 {
		t.Fatalf("OCR %08X %v", ocr, err)
	}
	if ok, err := sd.IsPresent(); err != nil || !ok {
		t.Fatalf("IsPresent %v %v", ok, err)
	}
}

func TestSDCRCError(t *testing.T) {
	m := newSDModel(true, true)
	sd, err := NewSDSPIFull(m, SDSPIOptions{})
	if err != nil {
		t.Fatal(err)
	}
	m.corruptReadCRC = true
	r := make([]byte, 512)
	if err := sd.ReadBlock(1, r); !errors.Is(err, storage.ErrCRC) {
		t.Fatalf("want ErrCRC, got %v", err)
	}
	if err := sd.SetCRC(false); err != nil {
		t.Fatal(err)
	}
	if err := sd.ReadBlock(1, r); err != nil {
		t.Fatalf("CRC off: %v", err)
	}
}

func TestSDBlockDeviceContractSharedWithRAM(t *testing.T) {
	sd, err := NewSDSPIFull(newSDModel(true, true), SDSPIOptions{})
	if err != nil {
		t.Fatal(err)
	}
	for name, dev := range map[string]storage.BlockDevice{"SD": sd, "RAM": storage.NewRamBlockDevice(512, 64, 1, false)} {
		w := bytes.Repeat([]byte{0xA5}, 512)
		if err := dev.Write(3, w); err != nil {
			t.Fatalf("%s write: %v", name, err)
		}
		r := make([]byte, 512)
		if err := dev.Read(3, r); err != nil || !bytes.Equal(w, r) {
			t.Fatalf("%s roundtrip: %v", name, err)
		}
		if err := dev.Read(dev.BlockCount(), r); !errors.Is(err, storage.ErrOutOfRange) {
			t.Fatalf("%s out of range: %v", name, err)
		}
	}
}
