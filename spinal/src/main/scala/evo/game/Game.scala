package evo.game

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import evo.types._

/**
 * Game engine: runs one frame of 2-player physics per `go` pulse.
 *
 * FSM cycle:
 *   IDLE -> (go) -> PHASE1_SETUP -> PHASE1 -> PHASE2_SETUP -> PHASE2 -> IDLE
 *   IDLE -> (init) -> INIT -> (wait for RNG warmup) -> IDLE
 *
 * The xormix32 RNG is instantiated externally; result/enable are wired through IO.
 */
class Game extends Component {
  val io = new Bundle {
    val init      = in Bool()
    val swapStart = in Bool()
    val seed      = in Bits(32 bits)
    val tilemap   = in(Tilemap())

    val p1Input = in(PlayerInput())
    val p2Input = in(PlayerInput())

    val go        = in Bool()
    val done      = out Bool()
    val gamestate = out(GameState())

    // RNG interface (external xormix32)
    val rngResult = in Bits(96 bits)   // 3 streams * 32 bits
    val rngEnable = out Bool()
  }

  val INIT_CYCLES = 7

  // --- Registers ---
  val gs            = Reg(GameState()) init(GameState().getZero)
  val p1SpawnTile   = Reg(TilePos())
  val p2SpawnTile   = Reg(TilePos())
  val coinSpawnTile = Reg(TilePos())
  val swapStartR    = Reg(Bool()) init False
  val p1InputR      = Reg(PlayerInput())
  val p2InputR      = Reg(PlayerInput())
  val initCounter   = Reg(UInt(4 bits)) init INIT_CYCLES
  val p1Setup1      = Reg(PlayerPhysics.Setup1())
  val p2Setup1      = Reg(PlayerPhysics.Setup1())
  val p1Setup2      = Reg(PlayerPhysics.Setup2())
  val p2Setup2      = Reg(PlayerPhysics.Setup2())

  // --- Continuous assignments ---
  io.gamestate := gs

  // Always sample spawns from RNG
  p1SpawnTile   := TilemapOps.sampleSpawn(io.tilemap, io.rngResult(31 downto 0))
  p2SpawnTile   := TilemapOps.sampleSpawn(io.tilemap, io.rngResult(63 downto 32))
  coinSpawnTile := TilemapOps.sampleSpawn(io.tilemap, io.rngResult(95 downto 64))

  // --- FSM ---
  val fsm = new StateMachine {
    val idle        = new State with EntryPoint
    val initS       = new State
    val phase1Setup = new State
    val phase1      = new State
    val phase2Setup = new State
    val phase2      = new State

    io.rngEnable := isActive(initS) || isActive(phase1)
    io.done      := isActive(idle)

    idle.whenIsActive {
      when(io.init) {
        initCounter := INIT_CYCLES
        swapStartR  := io.swapStart
        goto(initS)
      } elsewhen(io.go) {
        p1InputR := io.p1Input
        p2InputR := io.p2Input
        goto(phase1Setup)
      }
    }

    initS.whenIsActive {
      when(io.init) {
        initCounter := INIT_CYCLES
        swapStartR  := io.swapStart
      } otherwise {
        initCounter := initCounter - 1
        when(initCounter === 0) {
          // Reset player state
          for (p <- Seq(gs.p1, gs.p2)) {
            p.vel.x := 0.0; p.vel.y := 0.0
            p.score := 0;   p.deadTimeout := 0
          }
          gs.age := 0

          // Set spawn positions
          when(swapStartR) {
            gs.p1.pos := TilemapOps.tilePosToF4Vec(p2SpawnTile)
            gs.p2.pos := TilemapOps.tilePosToF4Vec(p1SpawnTile)
          } otherwise {
            gs.p1.pos := TilemapOps.tilePosToF4Vec(p1SpawnTile)
            gs.p2.pos := TilemapOps.tilePosToF4Vec(p2SpawnTile)
          }
          gs.coinPos := coinSpawnTile

          goto(idle)
        }
      }
    }

    phase1Setup.whenIsActive {
      p1Setup1 := PlayerPhysics.phase1Setup(gs.p1, io.tilemap)
      p2Setup1 := PlayerPhysics.phase1Setup(gs.p2, io.tilemap)
      goto(phase1)
    }

    phase1.whenIsActive {
      gs.p1 := PlayerPhysics.phase1Update(gs.p1, gs.p2, p1InputR, p1Setup1, io.tilemap)
      gs.p2 := PlayerPhysics.phase1Update(gs.p2, gs.p1, p2InputR, p2Setup1, io.tilemap)
      goto(phase2Setup)
    }

    phase2Setup.whenIsActive {
      p1Setup2 := PlayerPhysics.phase2Setup(gs.p1, io.tilemap, p1Setup1)
      p2Setup2 := PlayerPhysics.phase2Setup(gs.p2, io.tilemap, p2Setup1)
      goto(phase2)
    }

    phase2.whenIsActive {
      gs.p1 := PlayerPhysics.phase2Update(gs.p1, p1SpawnTile, gs.coinPos, p1Setup2, io.tilemap)
      gs.p2 := PlayerPhysics.phase2Update(gs.p2, p2SpawnTile, gs.coinPos, p2Setup2, io.tilemap)

      when(PlayerPhysics.isTouchingCoin(gs.p1, gs.coinPos) ||
           PlayerPhysics.isTouchingCoin(gs.p2, gs.coinPos)) {
        gs.coinPos := coinSpawnTile
      }

      goto(idle)
    }
  }
}
