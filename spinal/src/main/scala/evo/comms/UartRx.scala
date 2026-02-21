package evo.comms

import spinal.core._

/**
 * UART Receiver.
 *
 * Receives 8 bits of serial data, one start bit, one stop bit, and no parity bit.
 * When receive is complete, rxDv will be driven high for one clock cycle.
 *
 * Ported from fpga/src/imports/uart_rx.vhd
 *
 * @param clksPerBit Clock cycles per UART bit.
 *                   For 10 MHz clock and 115200 baud: 10000000 / 115200 = 87
 */
class UartRx(clksPerBit: Int = 115) extends Component {
  val io = new Bundle {
    val rxSerial = in  Bool()
    val rxDv     = out Bool()
    val rxByte   = out Bits(8 bits)
  }

  // --- State enumeration ---
  object RxState extends SpinalEnum(binarySequential) {
    val IDLE, RX_START_BIT, RX_DATA_BITS, RX_STOP_BIT, CLEANUP = newElement()
  }

  // Double-register the incoming data to mitigate metastability
  val rxDataR = Reg(Bool()) init True
  val rxData  = Reg(Bool()) init True

  rxDataR := io.rxSerial
  rxData  := rxDataR

  // FSM state register
  val state = Reg(RxState()) init RxState.IDLE

  // Clock counter for bit timing
  val clkCount = Reg(UInt(log2Up(clksPerBit) bits)) init 0

  // Bit index for data reception (0 to 7)
  val bitIndex = Reg(UInt(3 bits)) init 0

  // Received byte register
  val rxByteReg = Reg(Bits(8 bits)) init 0

  // Data valid register
  val rxDvReg = Reg(Bool()) init False

  // --- FSM ---
  switch(state) {
    is(RxState.IDLE) {
      rxDvReg   := False
      clkCount  := 0
      bitIndex  := 0

      when(!rxData) {
        state := RxState.RX_START_BIT
      }
    }
    is(RxState.RX_START_BIT) {
      when(clkCount === (clksPerBit - 1) / 2) {
        when(!rxData) {
          clkCount := 0
          state    := RxState.RX_DATA_BITS
        } otherwise {
          state := RxState.IDLE
        }
      } otherwise {
        clkCount := clkCount + 1
      }
    }
    is(RxState.RX_DATA_BITS) {
      when(clkCount < clksPerBit - 1) {
        clkCount := clkCount + 1
      } otherwise {
        clkCount := 0
        rxByteReg(bitIndex) := rxData

        when(bitIndex < 7) {
          bitIndex := bitIndex + 1
        } otherwise {
          bitIndex := 0
          state    := RxState.RX_STOP_BIT
        }
      }
    }
    is(RxState.RX_STOP_BIT) {
      when(clkCount < clksPerBit - 1) {
        clkCount := clkCount + 1
      } otherwise {
        rxDvReg  := True
        clkCount := 0
        state    := RxState.CLEANUP
      }
    }
    is(RxState.CLEANUP) {
      state   := RxState.IDLE
      rxDvReg := False
    }
  }

  io.rxDv   := rxDvReg
  io.rxByte := rxByteReg
}
