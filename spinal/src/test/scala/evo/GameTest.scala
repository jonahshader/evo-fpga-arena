package evo

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.game._
import evo.types._
import evo.types.MapConfig._

class GameTest extends AnyFunSuite {

  /** Helper to build a simple test tilemap in simulation. */
  def setupTestTilemap(dut: Game): Unit = {
    val tm = dut.io.tilemap

    tm.width  #= 8
    tm.height #= 8

    // Fill all tiles with AIR
    for (y <- 0 until MAP_MAX_SIZE_TILES; x <- 0 until MAP_MAX_SIZE_TILES) {
      tm.tiles(y)(x) #= TileType.AIR
    }

    // Ground row at y=7 (bottom in storage = y=0 in game coords)
    for (x <- 0 until 8) {
      tm.tiles(7)(x) #= TileType.GROUND
    }

    // Floating platform at storage y=4 (game y=3)
    for (x <- 3 to 5) {
      tm.tiles(4)(x) #= TileType.GROUND
    }

    // Set spawns
    for (i <- 0 until MAP_MAX_SPAWNS) {
      tm.spawn(i).x #= (i % 6) + 2
      tm.spawn(i).y #= 1
    }
    tm.numSpawn     #= 6
    tm.numSpawnBits #= 3
  }

  test("Game should elaborate and run init + one frame") {
    SimConfig.withWave.compile(new Game).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.init      #= false
      dut.io.swapStart #= false
      dut.io.seed      #= 0x12345678L
      dut.io.go        #= false
      dut.io.p1Input.left  #= false
      dut.io.p1Input.right #= false
      dut.io.p1Input.jump  #= false
      dut.io.p2Input.left  #= false
      dut.io.p2Input.right #= false
      dut.io.p2Input.jump  #= false
      dut.io.rngResult #= 0L

      setupTestTilemap(dut)

      // Let it settle
      dut.clockDomain.waitRisingEdge(5)

      // Trigger init
      dut.io.init #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.init #= false

      // Wait for init to complete
      dut.clockDomain.waitRisingEdge(15)
      assert(dut.io.done.toBoolean, "Game should be idle after init")

      // Run a frame
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for frame to complete (4 phases + margin)
      dut.clockDomain.waitRisingEdge(10)
      assert(dut.io.done.toBoolean, "Game should be idle after frame")

      println("Game test passed: elaboration + init + 1 frame")
    }
  }
}
