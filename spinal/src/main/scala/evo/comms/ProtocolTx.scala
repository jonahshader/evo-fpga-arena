package evo.comms

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import evo.types._

/**
 * Protocol TX: Serializes Neuroevolution output signals into byte-wide AXI-Stream.
 *
 * Monitors pulse inputs with priority arbitration. When triggered, constructs a
 * message (4-byte header + payload) and shifts it out one byte at a time.
 *
 * See doc/COMMS_PROTOCOL.md for message formats.
 */
class ProtocolTx extends Component {
  val axisConfig = Axi4StreamConfig(dataWidth = 1, useLast = true)

  val io = new Bundle {
    val stream = master(Axi4Stream(axisConfig))

    // From Neuroevolution
    val stateChange          = in Bool()
    val neState              = in(NeState())
    val gaState              = in(GaStateBundle())
    val gaStateSend          = in Bool()
    val gameState            = in(GameState())
    val gameStateSend        = in Bool()
    val bramDumpParam        = in Bits(BramConfig.BRAM_WIDTH bits)
    val bramDumpParamIndex   = in UInt(BramConfig.BRAM_ADDR_BITS bits)
    val bramDumpParamValid   = in Bool()
    val testGo               = in Bool()

    val ready = out Bool()
  }

  import CommsDefs._
  import CommsDefs.TelId._

  // --- FSM ---
  object TxState extends SpinalEnum {
    val IDLE, SEND_HEADER, SEND_PAYLOAD = newElement()
  }

  val state = Reg(TxState()) init TxState.IDLE
  val byteCounter = Reg(UInt(16 bits)) init 0

  // Message being sent
  val msgType   = Reg(UInt(8 bits)) init 0
  val msgLength = Reg(UInt(16 bits)) init 0

  // Latched data for current message
  val latchedNeState   = Reg(UInt(8 bits)) init 0
  val latchedGaState   = Reg(GaStateBundle())
  val latchedGameState = Reg(GameState())

  // BRAM dump buffer — accumulate params as they arrive
  val bramReg = Mem(Bits(BramConfig.BRAM_WIDTH bits), BramConfig.BRAM_DEPTH)
  val bramDumpReady = RegInit(False)
  val bramDumpParamValidPrev = RegInit(False)

  bramDumpParamValidPrev := io.bramDumpParamValid
  when(io.bramDumpParamValid) {
    bramReg.write(io.bramDumpParamIndex, io.bramDumpParam)
  }
  // Edge detect: falling edge of bramDumpParamValid → dump is complete
  when(bramDumpParamValidPrev && !io.bramDumpParamValid) {
    bramDumpReady := True
  }

  // --- Output defaults ---
  io.stream.valid := False
  io.stream.payload.data := 0
  io.stream.payload.last := False
  io.ready := state === TxState.IDLE

  // --- Payload byte mux ---
  val payloadByte = Bits(8 bits)
  payloadByte := 0

  switch(msgType) {
    is(STATE_CHANGE) {
      when(byteCounter === 0) { payloadByte := latchedNeState.asBits.resized }
    }
    is(GA_STATUS) {
      switch(byteCounter) {
        is(0) { payloadByte := latchedGaState.currentGen(7 downto 0).asBits }
        is(1) { payloadByte := latchedGaState.currentGen(15 downto 8).asBits }
        is(2) { payloadByte := latchedGaState.referenceFitness(7 downto 0).asBits }
        is(3) { payloadByte := latchedGaState.referenceFitness(15 downto 8).asBits }
      }
    }
    is(GAME_STATE) {
      switch(byteCounter) {
        is(0)  { payloadByte := latchedGameState.p1.pos.x.raw(7 downto 0).asBits }
        is(1)  { payloadByte := latchedGameState.p1.pos.x.raw(15 downto 8).asBits }
        is(2)  { payloadByte := latchedGameState.p1.pos.y.raw(7 downto 0).asBits }
        is(3)  { payloadByte := latchedGameState.p1.pos.y.raw(15 downto 8).asBits }
        is(4)  { payloadByte := latchedGameState.p1.score(7 downto 0).asBits }
        is(5)  { payloadByte := latchedGameState.p1.score(15 downto 8).asBits }
        is(6)  { payloadByte := latchedGameState.p1.deadTimeout.asBits }
        is(7)  { payloadByte := latchedGameState.p2.pos.x.raw(7 downto 0).asBits }
        is(8)  { payloadByte := latchedGameState.p2.pos.x.raw(15 downto 8).asBits }
        is(9)  { payloadByte := latchedGameState.p2.pos.y.raw(7 downto 0).asBits }
        is(10) { payloadByte := latchedGameState.p2.pos.y.raw(15 downto 8).asBits }
        is(11) { payloadByte := latchedGameState.p2.score(7 downto 0).asBits }
        is(12) { payloadByte := latchedGameState.p2.score(15 downto 8).asBits }
        is(13) { payloadByte := latchedGameState.p2.deadTimeout.asBits }
        is(14) { payloadByte := latchedGameState.coinPos.x.asBits.resized }
        is(15) { payloadByte := latchedGameState.coinPos.y.asBits.resized }
        is(16) { payloadByte := latchedGameState.age(7 downto 0).asBits }
        is(17) { payloadByte := latchedGameState.age(15 downto 8).asBits }
        // 18, 19 = padding (default 0)
      }
    }
    is(BRAM_DUMP_RESP) {
      payloadByte := bramReg.readAsync(byteCounter.resized).resized
    }
  }

  // --- FSM logic ---
  switch(state) {
    is(TxState.IDLE) {
      // Priority arbitration (matches VHDL order)
      when(io.gaStateSend) {
        latchedGaState := io.gaState
        msgType        := GA_STATUS
        msgLength      := PayloadSize.GA_STATUS
        state          := TxState.SEND_HEADER
        byteCounter    := 0
      } elsewhen (io.gameStateSend) {
        latchedGameState := io.gameState
        msgType          := GAME_STATE
        msgLength        := PayloadSize.GAME_STATE
        state            := TxState.SEND_HEADER
        byteCounter      := 0
      } elsewhen (io.testGo) {
        msgType   := TEST_RESP
        msgLength := 0
        state     := TxState.SEND_HEADER
        byteCounter := 0
      } elsewhen (bramDumpReady) {
        msgType       := BRAM_DUMP_RESP
        msgLength     := PayloadSize.BRAM_DUMP
        state         := TxState.SEND_HEADER
        byteCounter   := 0
        bramDumpReady := False
      } elsewhen (io.stateChange) {
        // Map NeState enum to integer
        latchedNeState := 0
        when(io.neState === NeState.IDLE)     { latchedNeState := 0 }
        when(io.neState === NeState.TRAINING) { latchedNeState := 1 }
        when(io.neState === NeState.PLAYING)  { latchedNeState := 2 }
        msgType   := STATE_CHANGE
        msgLength := PayloadSize.STATE_CHANGE
        state     := TxState.SEND_HEADER
        byteCounter := 0
      }
    }

    is(TxState.SEND_HEADER) {
      io.stream.valid := True

      switch(byteCounter) {
        is(0) { io.stream.payload.data := msgType.asBits.resized }
        is(1) { io.stream.payload.data := 0 } // flags
        is(2) { io.stream.payload.data := msgLength(7 downto 0).asBits }
        is(3) { io.stream.payload.data := msgLength(15 downto 8).asBits }
      }

      // For zero-length messages, last beat is the final header byte
      when(byteCounter === 3 && msgLength === 0) {
        io.stream.payload.last := True
      }

      when(io.stream.fire) {
        when(byteCounter === 3) {
          when(msgLength === 0) {
            state := TxState.IDLE
          } otherwise {
            state       := TxState.SEND_PAYLOAD
            byteCounter := 0
          }
        } otherwise {
          byteCounter := byteCounter + 1
        }
      }
    }

    is(TxState.SEND_PAYLOAD) {
      io.stream.valid        := True
      io.stream.payload.data := payloadByte

      when(byteCounter === msgLength - 1) {
        io.stream.payload.last := True
      }

      when(io.stream.fire) {
        when(byteCounter === msgLength - 1) {
          state := TxState.IDLE
        } otherwise {
          byteCounter := byteCounter + 1
        }
      }
    }
  }
}
