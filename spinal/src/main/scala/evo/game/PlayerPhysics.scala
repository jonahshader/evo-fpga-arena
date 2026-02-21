package evo.game

import spinal.core._
import spinal.lib._
import evo.types._
import evo.types.F4.SFixOps
import evo.types.MapConfig._
import evo.types.PlayerConfig._
import evo.types.PhysicsConstants._

/**
 * Player physics: setup, velocity, collision, and scoring.
 *
 * Mirrors player_funs.vhd. Each frame runs through 4 sub-states:
 *   PHASE1_SETUP -> PHASE1 -> PHASE2_SETUP -> PHASE2
 *
 * Setup phases compute tile lookups around the player's bounding box.
 * Phase 1 applies velocity changes (gravity, input, player-player collision).
 * Phase 2 resolves wall/floor/ceiling collisions and scoring.
 */
object PlayerPhysics {

  // ---- Setup bundles ----

  /** Phase 1 setup: tiles at player's current bounding box corners. */
  case class Setup1() extends Bundle {
    val xTileLeft     = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val xTileRight    = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val yTileDown     = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val yTileUp       = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val leftTile      = TileType()
    val rightTile     = TileType()
    val downLeftTile  = TileType()
    val downRightTile = TileType()
    val grounded      = Bool()
  }

  /** Phase 2 setup: tiles at player's post-velocity bounding box corners. */
  case class Setup2() extends Bundle {
    val xnTileLeft  = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val xnTileRight = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val ynTileDown  = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val ynTileUp    = SInt(MAP_MAX_SIZE_PX_BITS + 1 bits)
    val left1  = TileType()
    val left2  = TileType()
    val right1 = TileType()
    val right2 = TileType()
    val down1  = TileType()
    val down2  = TileType()
    val up1    = TileType()
    val up2    = TileType()
  }

  // ---- Setup functions ----

  /** Compute phase 1 setup: tile lookups at player's current bounding box. */
  def phase1Setup(p: Player, tm: Tilemap): Setup1 = {
    val s = Setup1()

    val xLow = F4.toInt(p.pos.x)
    val yLow = F4.toInt(p.pos.y)

    s.xTileLeft  := TilemapOps.pixelToTile(xLow.resized).resized
    s.xTileRight := TilemapOps.pixelToTile((xLow + PLAYER_WIDTH - 1).resized).resized
    s.yTileDown  := TilemapOps.pixelToTile(yLow.resized).resized
    s.yTileUp    := TilemapOps.pixelToTile((yLow + PLAYER_HEIGHT - 1).resized).resized

    s.leftTile      := TilemapOps.getTile(tm, s.xTileLeft.resized,  s.yTileDown.resized)
    s.rightTile     := TilemapOps.getTile(tm, s.xTileRight.resized, s.yTileDown.resized)
    s.downLeftTile  := TilemapOps.getTile(tm, s.xTileLeft.resized,  (s.yTileDown - 1).resized)
    s.downRightTile := TilemapOps.getTile(tm, s.xTileRight.resized, (s.yTileDown - 1).resized)

    // Grounded: y is exactly on a tile boundary with a solid tile below
    val yAligned = TilemapOps.tileToPixel(s.yTileDown.resized)
    s.grounded := (yAligned === F4.toInt(p.pos.y).resized) &&
                  (TileType.isSolid(s.downLeftTile) || TileType.isSolid(s.downRightTile))

    s
  }

  /** Compute phase 2 setup: tile lookups after velocity integration. */
  def phase2Setup(p: Player, tm: Tilemap, s1: Setup1): Setup2 = {
    val s = Setup2()

    val xnLow = F4.toInt(p.pos.x)
    val ynLow = F4.toInt(p.pos.y)

    s.xnTileLeft  := TilemapOps.pixelToTile(xnLow.resized).resized
    s.xnTileRight := TilemapOps.pixelToTile((xnLow + PLAYER_WIDTH - 1).resized).resized
    s.ynTileDown  := TilemapOps.pixelToTile(ynLow.resized).resized
    s.ynTileUp    := TilemapOps.pixelToTile((ynLow + PLAYER_HEIGHT - 1).resized).resized

    // Cross-axis lookups (new x with old y, new y with old x)
    s.left1  := TilemapOps.getTile(tm, s.xnTileLeft.resized,  s1.yTileDown.resized)
    s.left2  := TilemapOps.getTile(tm, s.xnTileLeft.resized,  s1.yTileUp.resized)
    s.right1 := TilemapOps.getTile(tm, s.xnTileRight.resized, s1.yTileDown.resized)
    s.right2 := TilemapOps.getTile(tm, s.xnTileRight.resized, s1.yTileUp.resized)
    s.down1  := TilemapOps.getTile(tm, s1.xTileLeft.resized,  s.ynTileDown.resized)
    s.down2  := TilemapOps.getTile(tm, s1.xTileRight.resized, s.ynTileDown.resized)
    s.up1    := TilemapOps.getTile(tm, s1.xTileLeft.resized,  s.ynTileUp.resized)
    s.up2    := TilemapOps.getTile(tm, s1.xTileRight.resized, s.ynTileUp.resized)

    s
  }

  /** Check if a player's center tile overlaps with the coin position. */
  def isTouchingCoin(p: Player, coinPos: TilePos): Bool = {
    val px = F4.toInt(p.pos.x)
    val py = F4.toInt(p.pos.y)
    val centerX = TilemapOps.pixelToTile((px + PLAYER_WIDTH / 2).resized)
    val centerY = TilemapOps.pixelToTile((py + PLAYER_HEIGHT / 2).resized)
    (centerX === coinPos.x.asSInt.resized) && (centerY === coinPos.y.asSInt.resized)
  }

  // ---- Physics update functions ----

  /**
   * Phase 1: velocity update (gravity, input, player-player collision).
   *
   * Uses staged intermediate wires to avoid combinatorial loops — SpinalHDL
   * signals are concurrent, so reading a signal after conditionally writing it
   * creates feedback. Each stage feeds forward into the next.
   */
  def phase1Update(
    p: Player, other: Player, input: PlayerInput,
    s1: Setup1, tm: Tilemap
  ): Player = {
    val pn = Player()
    pn := p  // default: no change (handles dead case)

    when(p.deadTimeout === 0) {
      // --- Environment detection ---
      val inWater = TileType.isWater(s1.leftTile) || TileType.isWater(s1.rightTile)
      val onIce   = (s1.downLeftTile === TileType.ICE) || (s1.downRightTile === TileType.ICE)

      val grav    = F4()
      val moveAcc = F4()
      when(inWater) { grav := GRAVITY_WATER } otherwise { grav := GRAVITY }
      when(onIce)        { moveAcc := MOVE_ACCEL_ICE }
        .elsewhen(inWater) { moveAcc := MOVE_ACCEL_WATER }
        .otherwise         { moveAcc := MOVE_ACCEL }

      // --- Y velocity (gravity + jump) ---
      val rawVelY = new Area {
        val out = F4()
        out := p.vel.y

        when(s1.grounded) {
          when(s1.downLeftTile === TileType.SPRING || s1.downRightTile === TileType.SPRING) {
            out := SPRING_VEL
          } elsewhen(input.jump) {
            out := JUMP_VEL
          }
        } otherwise {
          out := (p.vel.y + grav).asF4
          when(input.jump) {
            out := (p.vel.y + grav + F4(JUMP_MIDAIR_ACCEL)).asF4
          }
        }
      }

      val clampedVelY = new Area {
        val out = F4()
        out := rawVelY.out
        when(!s1.grounded && rawVelY.out < F4(FALL_MAX_VEL)) {
          out := FALL_MAX_VEL
        }
      }

      // --- X velocity (input + friction) ---
      val rawVelX = new Area {
        val out = F4()
        out := p.vel.x

        when(input.left && !input.right) {
          out := (p.vel.x - moveAcc).asF4
        } elsewhen(input.right && !input.left) {
          out := (p.vel.x + moveAcc).asF4
        } otherwise {
          when(s1.grounded && !onIce) {
            when(p.vel.x > F4(0.0)) {
              when(p.vel.x >= moveAcc) {
                out := (p.vel.x - moveAcc).asF4
              } otherwise {
                out := 0.0
              }
            } elsewhen(p.vel.x < F4(0.0)) {
              when(p.vel.x <= moveAcc.negated) {
                out := (p.vel.x + moveAcc).asF4
              } otherwise {
                out := 0.0
              }
            }
          }
        }
      }

      // --- Player-player collision (may override X velocity) ---
      val collision = new Area {
        val velX        = F4()
        val score       = SInt(16 bits)
        val deadTimeout = UInt(8 bits)
        velX        := rawVelX.out
        score       := p.score
        deadTimeout := p.deadTimeout

        when(other.deadTimeout === 0) {
          val dy = p.pos.y - other.pos.y
          val dx = p.pos.x - other.pos.x

          val absDy = F4()
          val absDx = F4()
          when(dy.raw < 0) { absDy.raw := (-dy.raw).resized } otherwise { absDy.raw := dy.raw.resized }
          when(dx.raw < 0) { absDx.raw := (-dx.raw).resized } otherwise { absDx.raw := dx.raw.resized }

          when(absDy < F4(PLAYER_HEIGHT.toDouble) && absDx <= F4(PLAYER_WIDTH.toDouble)) {
            val killed = other.pos.y.raw >= (p.pos.y + F4(PLAYER_KILL_HEIGHT.toDouble)).raw.resized
            val killer = p.pos.y.raw >= (other.pos.y + F4(PLAYER_KILL_HEIGHT.toDouble)).raw.resized

            when(killed) {
              deadTimeout := DEAD_TIMEOUT
            } elsewhen(killer) {
              score := p.score + POINTS_PER_KILL
            }

            // Push (only when not a kill event)
            when(!killed && !killer) {
              when(p.pos.x > other.pos.x) {
                velX := (p.vel.x + other.pos.x - p.pos.x + F4(PLAYER_WIDTH.toDouble)).asF4
              } elsewhen(p.pos.x < other.pos.x) {
                velX := (p.vel.x + other.pos.x - p.pos.x - F4(PLAYER_WIDTH.toDouble)).asF4
              }
            }
          }
        }
      }

      // --- Clamp X velocity ---
      val clampedVelX = new Area {
        val out = F4()
        out := collision.velX
        when(collision.velX < F4(-MOVE_MAX_VEL)) {
          out := -MOVE_MAX_VEL
        }
        when(collision.velX > F4(MOVE_MAX_VEL)) {
          out := MOVE_MAX_VEL
        }
      }

      // --- Final assignment ---
      pn.vel.x       := clampedVelX.out
      pn.vel.y       := clampedVelY.out
      pn.score       := collision.score
      pn.deadTimeout := collision.deadTimeout
      pn.pos.x       := (p.pos.x + clampedVelX.out).asF4
      pn.pos.y       := (p.pos.y + clampedVelY.out).asF4
    }

    pn
  }

  /** Phase 2: wall/floor/ceiling collision resolution and coin scoring. */
  def phase2Update(
    p: Player, pSpawn: TilePos, coinPos: TilePos,
    s2: Setup2, tm: Tilemap
  ): Player = {
    val pn = Player()
    pn := p  // default: no change

    when(p.deadTimeout > 1) {
      pn.deadTimeout := p.deadTimeout - 1
    } elsewhen(p.deadTimeout === 1) {
      pn.deadTimeout := 0
      pn.pos := TilemapOps.tilePosToF4Vec(pSpawn)
      pn.vel.x := 0.0
      pn.vel.y := 0.0
    } elsewhen(p.deadTimeout === 0) {
      val horizontal = new Area {
        val posX = F4()
        val velX = F4()
        posX := p.pos.x
        velX := p.vel.x

        when(p.vel.x < F4(0.0)) {
          when(TileType.isSolid(s2.left1) || TileType.isSolid(s2.left2)) {
            posX := F4.fromInt(TilemapOps.tileToPixel((s2.xnTileLeft + 1).resized))
            velX := 0.0
          }
        } elsewhen(p.vel.x > F4(0.0)) {
          when(TileType.isSolid(s2.right1) || TileType.isSolid(s2.right2)) {
            posX := (F4.fromInt(TilemapOps.tileToPixel(s2.xnTileRight.resized)) - F4(PLAYER_WIDTH.toDouble)).asF4
            velX := 0.0
          }
        }
      }

      val vertical = new Area {
        val posY = F4()
        val velY = F4()
        posY := p.pos.y
        velY := p.vel.y

        when(p.vel.y < F4(0.0)) {
          when(TileType.isSolid(s2.down1) || TileType.isSolid(s2.down2)) {
            posY := F4.fromInt(TilemapOps.tileToPixel((s2.ynTileDown + 1).resized))
            velY := 0.0
          }
        } elsewhen(p.vel.y > F4(0.0)) {
          when(TileType.isSolid(s2.up1) || TileType.isSolid(s2.up2)) {
            posY := (F4.fromInt(TilemapOps.tileToPixel(s2.ynTileUp.resized)) - F4(PLAYER_HEIGHT.toDouble)).asF4
            velY := 0.0
          }
        }
      }

      pn.pos.x := horizontal.posX
      pn.pos.y := vertical.posY
      pn.vel.x := horizontal.velX
      pn.vel.y := vertical.velY

      when(isTouchingCoin(p, coinPos)) {
        pn.score := p.score + POINTS_PER_COIN
      }
    }

    pn
  }
}
