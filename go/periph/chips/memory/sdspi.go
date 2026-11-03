package memory

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/storage"
)

// SD card (SPI mode) — specs/memory/sd_spi.md.
//
// SDSPIMinimal runs the SPI-mode init sequence in the constructor and offers
// single 512-byte block reads and writes. SDSPIFull adds multi-block
// transfers, erase, CID/CSD/OCR access and CRC checking, and implements
// storage.BlockDevice.
//
// The connection must implement connection.SPIExtensions (IdleClocks, SetSpeed,
// Select/Deselect, ReadInto) and clock 0xFF on MOSI while reading. Cards are
// 3.3 V only.

// SDSPIBlockSize is the block size of an SD card in SPI mode.
const SDSPIBlockSize = 512

const (
	sdspiInitTimeout  = 1000 * time.Millisecond
	sdspiTokenTimeout = 200 * time.Millisecond
	sdspiBusyTimeout  = 500 * time.Millisecond
	sdspiEraseTimeout = 30 * time.Second
)

// SDCardType is the card generation detected during init.
type SDCardType int

// Card generations.
const (
	SDCardNone SDCardType = iota
	SDCardV1
	SDCardV2
	SDCardSDHC
)

// SDSPIOptions configure SDSPIMinimal / SDSPIFull.
type SDSPIOptions struct {
	// SpeedHz is the data-phase clock after init (init always runs at
	// 400 kHz). Zero means 12 MHz.
	SpeedHz uint32
	// CRC enables CMD59 and sends/verifies CRC16 on data blocks.
	CRC bool
}

var sdspiCRC16Table = [16]uint16{
	0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
	0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
}

// SDCRC7 returns the CRC7 (polynomial x^7 + x^3 + 1) of data, not shifted.
func SDCRC7(data []byte) byte {
	var crc byte
	for _, b := range data {
		for i := 0; i < 8; i++ {
			crc <<= 1
			if (b^crc)&0x80 != 0 {
				crc ^= 0x09
			}
			b <<= 1
		}
	}
	return crc & 0x7F
}

// SDCRC16 returns the CRC16-CCITT (polynomial 0x1021, initial value 0) of data.
func SDCRC16(data []byte) uint16 {
	var crc uint16
	for _, b := range data {
		crc ^= uint16(b) << 8
		crc = (crc << 4) ^ sdspiCRC16Table[crc>>12]
		crc = (crc << 4) ^ sdspiCRC16Table[crc>>12]
	}
	return crc
}

// SDSPIMinimal is the minimal SD card driver: init plus single-block read/write.
type SDSPIMinimal struct {
	spi             connection.SPIExtensions
	speed           uint32
	wantCRC         bool
	crcOn           bool
	card            SDCardType
	blockAddressing bool
	blockCount      uint32
	csd             [16]byte
}

// NewSDSPIMinimal creates the driver and runs the SPI-mode init sequence.
//
// It returns storage.ErrNotReady if no (supported) card answers and
// storage.ErrTimeout if the card never leaves idle.
func NewSDSPIMinimal(spi connection.SPIExtensions, opt SDSPIOptions) (*SDSPIMinimal, error) {
	if opt.SpeedHz == 0 {
		opt.SpeedHz = 12_000_000
	}
	s := &SDSPIMinimal{spi: spi, speed: opt.SpeedHz, wantCRC: opt.CRC}
	if err := s.Begin(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *SDSPIMinimal) rx() (byte, error) {
	var b [1]byte
	if err := s.spi.ReadInto(b[:]); err != nil {
		return 0, err
	}
	return b[0], nil
}

func (s *SDSPIMinimal) waitReady(d time.Duration) error {
	start := time.Now()
	for {
		b, err := s.rx()
		if err != nil {
			return err
		}
		if b == 0xFF {
			return nil
		}
		if time.Since(start) >= d {
			return storage.ErrTimeout
		}
	}
}

func (s *SDSPIMinimal) release() error {
	if err := s.spi.Deselect(); err != nil {
		return err
	}
	return s.spi.IdleClocks(8) // let the card free MISO
}

// command sends a command with CS already selected and returns R1 (0xFF if no response).
func (s *SDSPIMinimal) command(cmd byte, arg uint32) (byte, error) {
	if cmd != 12 && cmd != 0 {
		if err := s.waitReady(sdspiBusyTimeout); err != nil {
			return 0xFF, err
		}
	}
	frame := [6]byte{0x40 | cmd, byte(arg >> 24), byte(arg >> 16), byte(arg >> 8), byte(arg), 0}
	frame[5] = SDCRC7(frame[:5])<<1 | 1
	if err := s.spi.Write(frame[:]); err != nil {
		return 0xFF, err
	}
	if cmd == 12 {
		if _, err := s.rx(); err != nil { // discard the stuff byte that follows CMD12
			return 0xFF, err
		}
	}
	for i := 0; i < 10; i++ { // Ncr <= 8 bytes
		r, err := s.rx()
		if err != nil {
			return 0xFF, err
		}
		if r&0x80 == 0 {
			return r, nil
		}
	}
	return 0xFF, nil
}

func (s *SDSPIMinimal) appCommand(cmd byte, arg uint32) (byte, error) {
	r, err := s.command(55, 0)
	if err != nil || r > 1 {
		return r, err
	}
	return s.command(cmd, arg)
}

func (s *SDSPIMinimal) readData(buf []byte) error {
	start := time.Now()
	var token byte
	for {
		t, err := s.rx()
		if err != nil {
			return err
		}
		if t != 0xFF {
			token = t
			break
		}
		if time.Since(start) >= sdspiTokenTimeout {
			return storage.ErrTimeout
		}
	}
	if token != 0xFE {
		return fmt.Errorf("%w: data error token 0x%02X", storage.ErrNotReady, token)
	}
	if err := s.spi.ReadInto(buf); err != nil {
		return err
	}
	var crc [2]byte
	if err := s.spi.ReadInto(crc[:]); err != nil {
		return err
	}
	if s.crcOn && uint16(crc[0])<<8|uint16(crc[1]) != SDCRC16(buf) {
		return storage.ErrCRC
	}
	return nil
}

func (s *SDSPIMinimal) writeData(token byte, data []byte) error {
	if err := s.waitReady(sdspiBusyTimeout); err != nil {
		return err
	}
	if err := s.spi.Write([]byte{token}); err != nil {
		return err
	}
	if token == 0xFD {
		return nil // stop-tran token has no payload
	}
	if err := s.spi.Write(data); err != nil {
		return err
	}
	crc := []byte{0xFF, 0xFF}
	if s.crcOn {
		c := SDCRC16(data)
		crc = []byte{byte(c >> 8), byte(c)}
	}
	if err := s.spi.Write(crc); err != nil {
		return err
	}
	resp, err := s.rx()
	if err != nil {
		return err
	}
	switch resp & 0x1F {
	case 0x05:
		return nil
	case 0x0B:
		return storage.ErrCRC
	default:
		return fmt.Errorf("%w: write rejected 0x%02X", storage.ErrNotReady, resp)
	}
}

func (s *SDSPIMinimal) readRegister(cmd byte) ([16]byte, error) {
	var out [16]byte
	if err := s.spi.Select(); err != nil {
		return out, err
	}
	r, err := s.command(cmd, 0)
	if err == nil && r != 0 {
		err = fmt.Errorf("%w: CMD%d R1=0x%02X", storage.ErrNotReady, cmd, r)
	}
	if err == nil {
		err = s.readData(out[:])
	}
	if rel := s.release(); err == nil {
		err = rel
	}
	return out, err
}

func (s *SDSPIMinimal) address(block uint32) uint32 {
	if s.blockAddressing {
		return block
	}
	return block * SDSPIBlockSize
}

// Begin runs the SPI-mode init sequence (also usable to re-init a swapped card).
func (s *SDSPIMinimal) Begin() error {
	s.card = SDCardNone
	s.blockCount = 0
	s.crcOn = false
	if err := s.spi.SetSpeed(400_000); err != nil {
		return err
	}
	if err := s.spi.Deselect(); err != nil {
		return err
	}
	if err := s.spi.IdleClocks(80); err != nil {
		return err
	}

	// CMD0 with CS low enters SPI mode; a fresh card needs a few tries.
	idle := false
	for i := 0; i < 10 && !idle; i++ {
		if err := s.spi.Select(); err != nil {
			return err
		}
		r, err := s.command(0, 0)
		if rel := s.release(); err == nil {
			err = rel
		}
		if err != nil {
			return err
		}
		idle = r == 0x01
	}
	if !idle {
		return storage.ErrNotReady
	}

	if err := s.spi.Select(); err != nil {
		return err
	}
	err := s.initAfterIdle()
	if rel := s.release(); err == nil {
		err = rel
	}
	if err != nil {
		s.card = SDCardNone
		return err
	}

	if err := s.spi.SetSpeed(s.speed); err != nil {
		return err
	}
	csd, err := s.readRegister(9)
	if err != nil {
		return err
	}
	s.csd = csd
	s.blockCount = sdspiCSDBlocks(csd)
	return nil
}

func (s *SDSPIMinimal) initAfterIdle() error {
	v2 := false
	r, err := s.command(8, 0x1AA)
	if err != nil {
		return err
	}
	switch {
	case r == 0x01:
		var r7 [4]byte
		if err := s.spi.ReadInto(r7[:]); err != nil {
			return err
		}
		if r7[2]&0x0F != 0x01 || r7[3] != 0xAA {
			return storage.ErrNotReady
		}
		v2 = true
	case r&0x04 == 0: // anything but "illegal command" (v1 card) is a failure
		return storage.ErrNotReady
	}

	hcs := uint32(0)
	if v2 {
		hcs = 0x40000000
	}
	start := time.Now()
	for {
		r, err = s.appCommand(41, hcs)
		if err != nil {
			return err
		}
		if r == 0x00 {
			break
		}
		if time.Since(start) >= sdspiInitTimeout {
			return storage.ErrTimeout
		}
	}

	s.card = SDCardV1
	if v2 {
		s.card = SDCardV2
	}
	s.blockAddressing = false
	if v2 {
		if r, err = s.command(58, 0); err != nil {
			return err
		}
		if r == 0x00 {
			var ocr [4]byte
			if err := s.spi.ReadInto(ocr[:]); err != nil {
				return err
			}
			if ocr[0]&0x40 != 0 {
				s.blockAddressing = true
				s.card = SDCardSDHC
			}
		}
	}
	if !s.blockAddressing {
		if _, err = s.command(16, SDSPIBlockSize); err != nil {
			return err
		}
	}
	if s.wantCRC {
		if r, err = s.command(59, 1); err != nil {
			return err
		}
		s.crcOn = r == 0x00
	}
	return nil
}

func sdspiCSDBlocks(csd [16]byte) uint32 {
	if csd[0]>>6 == 1 { // CSD v2: C_SIZE = CSD[69:48]
		csize := uint32(csd[7]&0x3F)<<16 | uint32(csd[8])<<8 | uint32(csd[9])
		return (csize + 1) * 1024
	}
	readBlLen := uint(csd[5] & 0x0F) // CSD v1
	csize := uint64(csd[6]&0x03)<<10 | uint64(csd[7])<<2 | uint64(csd[8]>>6)
	mult := uint(csd[9]&0x03)<<1 | uint(csd[10]>>7)
	return uint32(((csize + 1) << (mult + 2) << readBlLen) / SDSPIBlockSize)
}

// BlockCount returns the capacity in 512-byte blocks (from the CSD).
func (s *SDSPIMinimal) BlockCount() uint32 { return s.blockCount }

// SectorCount is an alias of BlockCount (FatFs terminology).
func (s *SDSPIMinimal) SectorCount() uint32 { return s.blockCount }

// CardType returns the card generation.
func (s *SDSPIMinimal) CardType() SDCardType { return s.card }

// ReadBlock reads one 512-byte block (CMD17) into buf.
func (s *SDSPIMinimal) ReadBlock(block uint32, buf []byte) error {
	if len(buf) != SDSPIBlockSize {
		return fmt.Errorf("sdspi: buffer must be %d bytes", SDSPIBlockSize)
	}
	if block >= s.blockCount {
		return storage.ErrOutOfRange
	}
	if err := s.spi.Select(); err != nil {
		return err
	}
	r, err := s.command(17, s.address(block))
	if err == nil && r != 0 {
		err = fmt.Errorf("%w: CMD17 R1=0x%02X", storage.ErrNotReady, r)
	}
	if err == nil {
		err = s.readData(buf)
	}
	if rel := s.release(); err == nil {
		err = rel
	}
	return err
}

// WriteBlock writes one 512-byte block (CMD24).
func (s *SDSPIMinimal) WriteBlock(block uint32, data []byte) error {
	if len(data) != SDSPIBlockSize {
		return fmt.Errorf("sdspi: data must be %d bytes", SDSPIBlockSize)
	}
	if block >= s.blockCount {
		return storage.ErrOutOfRange
	}
	if err := s.spi.Select(); err != nil {
		return err
	}
	r, err := s.command(24, s.address(block))
	if err == nil && r != 0 {
		err = fmt.Errorf("%w: CMD24 R1=0x%02X", storage.ErrNotReady, r)
	}
	if err == nil {
		err = s.writeData(0xFE, data)
	}
	if err == nil {
		err = s.waitReady(sdspiBusyTimeout)
	}
	if rel := s.release(); err == nil {
		err = rel
	}
	return err
}

// SDSPIFull is the full SD card driver. It extends SDSPIMinimal and
// implements storage.BlockDevice.
type SDSPIFull struct {
	SDSPIMinimal
	name string
}

var _ storage.BlockDevice = (*SDSPIFull)(nil)

// NewSDSPIFull creates the driver and runs the SPI-mode init sequence. CRC16
// checking is on by default (opt.CRC is forced true).
func NewSDSPIFull(spi connection.SPIExtensions, opt SDSPIOptions) (*SDSPIFull, error) {
	opt.CRC = true
	m, err := NewSDSPIMinimal(spi, opt)
	if err != nil {
		return nil, err
	}
	f := &SDSPIFull{SDSPIMinimal: *m}
	if cid, err := f.readRegister(10); err == nil {
		f.name = string(cid[3:8]) // PNM = CID[103:64]
	}
	return f, nil
}

// BlockSize implements storage.BlockDevice.
func (f *SDSPIFull) BlockSize() int { return SDSPIBlockSize }

// EraseSize implements storage.BlockDevice (an SD card needs no erase).
func (f *SDSPIFull) EraseSize() int { return SDSPIBlockSize }

// NeedsErase implements storage.BlockDevice.
func (f *SDSPIFull) NeedsErase() bool { return false }

// Read implements storage.BlockDevice (CMD18 for more than one block).
func (f *SDSPIFull) Read(block uint32, buf []byte) error {
	count := uint32(len(buf) / SDSPIBlockSize)
	if count == 0 {
		return nil
	}
	if uint64(block)+uint64(count) > uint64(f.blockCount) {
		return storage.ErrOutOfRange
	}
	if count == 1 {
		return f.ReadBlock(block, buf[:SDSPIBlockSize])
	}
	if err := f.spi.Select(); err != nil {
		return err
	}
	r, err := f.command(18, f.address(block))
	if err == nil && r != 0 {
		err = fmt.Errorf("%w: CMD18 R1=0x%02X", storage.ErrNotReady, r)
	}
	if err == nil {
		for i := uint32(0); i < count && err == nil; i++ {
			err = f.readData(buf[int(i)*SDSPIBlockSize : int(i+1)*SDSPIBlockSize])
		}
		_, _ = f.command(12, 0) // stop transmission, also after an error
		_ = f.waitReady(sdspiBusyTimeout)
	}
	if rel := f.release(); err == nil {
		err = rel
	}
	return err
}

// Write implements storage.BlockDevice (CMD25 for more than one block).
func (f *SDSPIFull) Write(block uint32, data []byte) error {
	count := uint32(len(data) / SDSPIBlockSize)
	if count == 0 {
		return nil
	}
	if uint64(block)+uint64(count) > uint64(f.blockCount) {
		return storage.ErrOutOfRange
	}
	if count == 1 {
		return f.WriteBlock(block, data[:SDSPIBlockSize])
	}
	if err := f.spi.Select(); err != nil {
		return err
	}
	_, _ = f.appCommand(23, count) // pre-erase hint; cards may ignore it
	r, err := f.command(25, f.address(block))
	if err == nil && r != 0 {
		err = fmt.Errorf("%w: CMD25 R1=0x%02X", storage.ErrNotReady, r)
	}
	if err == nil {
		for i := uint32(0); i < count && err == nil; i++ {
			err = f.writeData(0xFC, data[int(i)*SDSPIBlockSize:int(i+1)*SDSPIBlockSize])
		}
		_ = f.waitReady(sdspiBusyTimeout)
		_ = f.writeData(0xFD, nil)
		_, _ = f.rx() // stuff byte after the stop token
		if busy := f.waitReady(sdspiBusyTimeout); err == nil {
			err = busy
		}
	}
	if rel := f.release(); err == nil {
		err = rel
	}
	return err
}

// Erase implements storage.BlockDevice (CMD32/33/38; contents afterwards are card dependent).
func (f *SDSPIFull) Erase(block, count uint32) error {
	if count == 0 {
		return nil
	}
	if uint64(block)+uint64(count) > uint64(f.blockCount) {
		return storage.ErrOutOfRange
	}
	if err := f.spi.Select(); err != nil {
		return err
	}
	var err error
	for _, c := range []struct {
		cmd byte
		arg uint32
	}{{32, f.address(block)}, {33, f.address(block + count - 1)}, {38, 0}} {
		var r byte
		if r, err = f.command(c.cmd, c.arg); err != nil {
			break
		}
		if r != 0 {
			err = fmt.Errorf("%w: CMD%d R1=0x%02X", storage.ErrNotReady, c.cmd, r)
			break
		}
	}
	if err == nil {
		err = f.waitReady(sdspiEraseTimeout)
	}
	if rel := f.release(); err == nil {
		err = rel
	}
	return err
}

// Sync implements storage.BlockDevice: wait until the card has finished programming.
func (f *SDSPIFull) Sync() error {
	if err := f.spi.Select(); err != nil {
		return err
	}
	err := f.waitReady(sdspiBusyTimeout)
	if rel := f.release(); err == nil {
		err = rel
	}
	return err
}

// Info implements storage.BlockDevice.
func (f *SDSPIFull) Info() storage.BlockInfo {
	return storage.BlockInfo{BlockSize: SDSPIBlockSize, BlockCount: f.blockCount, EraseSize: SDSPIBlockSize, Name: f.name}
}

// IsBad implements storage.BlockDevice (an SD card hides bad blocks internally).
func (f *SDSPIFull) IsBad(uint32) bool { return false }

// MarkBad implements storage.BlockDevice (no-op).
func (f *SDSPIFull) MarkBad(uint32) error { return nil }

// CID returns the 16-byte CID register (manufacturer, name, revision, serial, date).
func (f *SDSPIFull) CID() ([16]byte, error) { return f.readRegister(10) }

// CSD returns the 16-byte CSD register as read during init.
func (f *SDSPIFull) CSD() [16]byte { return f.csd }

// OCR returns the OCR word (CMD58).
func (f *SDSPIFull) OCR() (uint32, error) {
	if err := f.spi.Select(); err != nil {
		return 0, err
	}
	var ocr uint32
	r, err := f.command(58, 0)
	if err == nil && r != 0 {
		err = fmt.Errorf("%w: CMD58 R1=0x%02X", storage.ErrNotReady, r)
	}
	if err == nil {
		var o [4]byte
		if err = f.spi.ReadInto(o[:]); err == nil {
			ocr = uint32(o[0])<<24 | uint32(o[1])<<16 | uint32(o[2])<<8 | uint32(o[3])
		}
	}
	if rel := f.release(); err == nil {
		err = rel
	}
	return ocr, err
}

// SetCRC enables or disables CMD59 and CRC16 checking.
func (f *SDSPIFull) SetCRC(enabled bool) error {
	if err := f.spi.Select(); err != nil {
		return err
	}
	v := uint32(0)
	if enabled {
		v = 1
	}
	r, err := f.command(59, v)
	if err == nil && r != 0 {
		err = fmt.Errorf("%w: CMD59 R1=0x%02X", storage.ErrNotReady, r)
	}
	if rel := f.release(); err == nil {
		err = rel
	}
	if err == nil {
		f.crcOn = enabled
	}
	return err
}

// SetSpeed changes the SCK frequency after init.
func (f *SDSPIFull) SetSpeed(hz uint32) error { return f.spi.SetSpeed(hz) }

// IsPresent reports whether a card responds to CMD13.
func (f *SDSPIFull) IsPresent() (bool, error) {
	if err := f.spi.Select(); err != nil {
		return false, err
	}
	r, err := f.command(13, 0)
	if err == nil && r != 0xFF {
		_, err = f.rx() // R2 second byte
	}
	if rel := f.release(); err == nil {
		err = rel
	}
	return err == nil && r != 0xFF, err
}
