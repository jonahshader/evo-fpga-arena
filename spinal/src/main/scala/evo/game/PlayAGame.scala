package evo.game

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import evo.types._
import evo.infra.Xormix32

/**
 * PlayAGame — wraps Game with a frame loop FSM.
 *
 * Ported from fpga/src/playagame.vhd
 *
 * FSM:
 *   IDLE -> (game_go) -> INIT_GAME -> WAIT_INPUT -> START_FRAME -> WAIT_FRAME_DONE
 *       -> (frame_limit reached?) -> IDLE (done) / WAIT_INPUT (next frame)
 *
 * Handles:
 * - Game initialization with seed
 * - NN input request/valid handshake
 * - Frame counting and score computation
 * - Instantiates Xormix32(3) RNG for the Game component
 */
class PlayAGame extends Component {
  val io = new Bundle {
    // Interface with fitness
    val swapStart  = in Bool()
    val seed       = in Bits(32 bits)
    val frameLimit = in UInt(16 bits)
    val gameGo     = in Bool()
    val gameDone   = out Bool()
    val scoreOutput = out SInt(16 bits)

    // Interface with NNs
    val p1Input        = in(PlayerInput())
    val p1InputValid   = in Bool()
    val p1RequestInput = out Bool()
    val p2Input        = in(PlayerInput())
    val p2InputValid   = in Bool()
    val p2RequestInput = out Bool()
    val gs             = out(GameState())
    val frameEndPulse  = out Bool() default(False)

    // Tilemap
    val tilemap = in(Tilemap())
  }

  // --- Registers ---
  val score        = Reg(SInt(16 bits)) init 0
  val frameCounter = Reg(UInt(16 bits)) init 0

  val gameInitR     = Reg(Bool()) init False
  val frameGoR      = Reg(Bool()) init False
  val requestInputR = Reg(Bool()) init False
  val gameDoneR     = Reg(Bool()) init False
  val frameDone     = Bool()

  val p1InputValidR = Reg(Bool()) init False
  val p2InputValidR = Reg(Bool()) init False
  val p1InputR      = Reg(PlayerInput())
  val p2InputR      = Reg(PlayerInput())

  // --- Xormix32 RNG (3 streams for Game) ---
  val rng = new Xormix32(streamCount = 3)
  val rngEnable = Bool()

  rng.io.rst    := gameInitR
  rng.io.seedX  := io.seed
  rng.io.seedY  := B(0, 96 bits)
  rng.io.enable := rngEnable

  // --- Game instance ---
  val game = new Game
  game.io.init      := gameInitR
  game.io.swapStart := io.swapStart
  game.io.seed      := io.seed
  game.io.tilemap   := io.tilemap
  game.io.p1Input   := p1InputR
  game.io.p2Input   := p2InputR
  game.io.go        := frameGoR
  game.io.rngResult := rng.io.result
  rngEnable         := game.io.rngEnable
  frameDone         := game.io.done
  io.gs             := game.io.gamestate

  // --- Outputs ---
  io.gameDone       := gameDoneR
  io.scoreOutput    := score
  io.p1RequestInput := requestInputR
  io.p2RequestInput := requestInputR
  io.frameEndPulse  := False

  // --- Pulse register defaults (cleared every cycle, set by FSM) ---
  gameInitR        := False
  frameGoR         := False
  requestInputR    := False
  gameDoneR        := False

  // Latch input valid and player inputs
  when(io.p1InputValid) {
    p1InputValidR := True
    p1InputR      := io.p1Input
  }
  when(io.p2InputValid) {
    p2InputValidR := True
    p2InputR      := io.p2Input
  }

  // --- FSM ---
  val fsm = new StateMachine {
    val idleS         = new State with EntryPoint
    val initGameS     = new State
    val waitInputS    = new State
    val startFrameS   = new State
    val waitFrameDoneS = new State

    idleS.whenIsActive {
      frameCounter  := 0
      p1InputValidR := False
      p2InputValidR := False
      when(io.gameGo) {
        score     := 0
        gameInitR := True
        goto(initGameS)
      }
    }

    initGameS.whenIsActive {
      requestInputR := True
      p1InputValidR := False
      p2InputValidR := False
      goto(waitInputS)
    }

    waitInputS.whenIsActive {
      when(p1InputValidR && p2InputValidR) {
        goto(startFrameS)
      }
    }

    startFrameS.whenIsActive {
      frameGoR      := True
      p1InputValidR := False
      p2InputValidR := False
      goto(waitFrameDoneS)
    }

    waitFrameDoneS.whenIsActive {
      when(frameDone) {
        io.frameEndPulse := True
        when(io.frameLimit === 0 || frameCounter < io.frameLimit - 1) {
          frameCounter  := frameCounter + 1
          requestInputR := True
          goto(waitInputS)
        } otherwise {
          score     := game.io.gamestate.p1.score - game.io.gamestate.p2.score
          gameDoneR := True
          goto(idleS)
        }
      }
    }
  }
}
