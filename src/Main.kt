import java.util.*
import kotlin.collections.HashSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

const val DEBUGGING = true

const val GAME_TURNS = 200

fun debug(vararg items: Any) {
  System.err.println(items.joinToString(" | "))
}

fun main() {
  val input = Scanner(System.`in`)

  val game = Game(input)

  repeat (GAME_TURNS) {
    game.newTurn()
    val actions = game.compute().toMutableList()
    if (actions.isEmpty()) actions += Wait()
    println(actions.joinToString(";"))
  }
}

enum class Strategy {
  Conquer, Expand
}

abstract class Action

data class Movement(val amount: Int, val from: Coordinate, val to: Coordinate) : Action() {
  override fun toString(): String {
    return "MOVE $amount $from $to"
  }
}

data class Build(val target: Coordinate) : Action() {
  override fun toString(): String {
    return "BUILD $target"
  }
}

data class Spawn(val amount: Int, val target: Coordinate) : Action() {
  override fun toString(): String {
    return "SPAWN $amount $target"
  }
}

class Wait : Action() {
  override fun toString() = "WAIT"
}

data class Message(val text: String): Action() {
  override fun toString() = "MESSAGE $text"
}


data class Coordinate(val x: Int, val y: Int) {
  fun getNeighbors(width: Int, height: Int): List<Coordinate> {
    return setOf(
      Coordinate(x, y + 1),
      Coordinate(x, y - 1),
      Coordinate(x + 1, y),
      Coordinate(x - 1, y),
    ).filter { it.x in (0..(width - 1)) && it.y in (0..(height - 1)) }
  }

  fun distanceTo(other: Coordinate): Int {
    return abs(this.x - other.x) + abs(this.y - other.y)
  }

  override fun toString() = "$x $y"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as Coordinate
    if (x != other.x) return false
    if (y != other.y) return false

    return true
  }

  override fun hashCode(): Int {
    var result = x
    result = 31 * result + y
    return result
  }
}

data class Cell(
  val coordinate: Coordinate,
) {
  val neighs = mutableListOf<Coordinate>()
  var owner = -1
  var scrapAmount = 0
  var units = 0
  var recycler = false
  var canBuild = false
  var canSpawn = false
  var inRangeOfRecycler = false

  fun isOwn() = this.owner == 1
  fun isGrass() = this.scrapAmount == 0
  fun addNeigh(neigh: Coordinate) = this.neighs.add(neigh)
}

class Game(private val input: Scanner) {
  private val width = input.nextInt()
  private val height = input.nextInt()

  private val cells = Array(width) { x -> Array(height) { y -> Coordinate(x, y) } }.flatten().associateWith { Cell(it) }

  private var myMatter = 0
  private var opponentMatter = 0

  private lateinit var zoneRange: IntRange

  private var turn = 0

  private lateinit var zone: String

  private var toRemove = listOf<Coordinate>()
  private var wall = listOf<Coordinate>()

  fun newTurn() {
    turn += 1
    readMatter()
    readCells()
  }

  fun compute(): List<Action> {
    if (turn == 1) findZone()
    calculateWall()
    return defineMovement()
  }

  private fun defineMovement(): List<Action> {
    val actions = mutableListOf<Action>()

    val strategy = if (toRemove.isEmpty() && wall.all { getCell(it).isGrass() || getCell(it).recycler }) Strategy.Expand else Strategy.Conquer

    val availableBots = cells.values.filter { it.isOwn() && it.units > 0 }.toMutableList()
    val myCells = cells.values.filter { it.isOwn() }
    val weakPoints = wall.filter {  !getCell(it).isGrass() || !getCell(it).recycler }.toMutableList()

    actions.add(Message(strategy.toString()))

    if (DEBUGGING) debug(strategy)

    val targets = when (strategy) {
      Strategy.Conquer -> getBetterRemovals().toMutableList()
      Strategy.Expand -> cells.values.filter {
        !it.isOwn() && !it.isGrass() && it.coordinate.x in zoneRange
      }.sortedByDescending { it.units }.toMutableList()
    }

    if (strategy == Strategy.Conquer && toRemove.isNotEmpty() && targets.isNotEmpty()) {
      val remove = targets.filter { it.isOwn() }
      remove.forEach {
        if (it.units > 0) {
          val move = if (zone == "LEFT") -1 else 1

          actions.add(Movement(it.units, it.coordinate, Coordinate(it.coordinate.x + move, it.coordinate.y)))
        }
        else actions.add(Build(it.coordinate))
      }
      targets.removeIf { it in remove }

      targets.forEach { c ->
        val to = c.coordinate

        val from = availableBots.minByOrNull { getDistance(it.coordinate, to) }

        if (from != null) {
          availableBots.removeIf { it.coordinate == from.coordinate }

          actions.add(Movement(from.units, from.coordinate, to))
        } else {
          val spawnIn = cells.values.filter { it.canSpawn && it.isOwn() }.minByOrNull { getDistance(it.coordinate, to) }

          if (spawnIn != null) actions.add(Spawn(1, spawnIn.coordinate))
        }
      }

      weakPoints
        .map { w -> myCells.filter { it.coordinate !in wall }.minBy { getDistance(w, it.coordinate) } }
        .groupBy { it.coordinate }
        .forEach { (k, v) -> actions.add(Spawn(v.size, k)) }

      return actions
    }

    val cellsWithBots = myCells.filter { it.units > 0 }.sortedByDescending { it.units }.toMutableList()

    if (strategy == Strategy.Conquer) {
      val assigned = weakPoints.associateWith { 0 }.toMutableMap()

      val botsPerCell = ceil(cellsWithBots.sumOf { it.units }.toDouble() / weakPoints.size).toInt()

      while (cellsWithBots.isNotEmpty()) {
        val cell = cellsWithBots.first()

        if (cell.units <= botsPerCell) cellsWithBots.removeFirst()

        val unitsToMove = minOf(cell.units, botsPerCell)

        cell.units -= unitsToMove

        val assignTo = assigned.minBy { it.value }

        actions.add(Movement(unitsToMove, cell.coordinate, assignTo.key))
        assigned[assignTo.key] = assignTo.value + unitsToMove
      }

      weakPoints
        .map { w -> myCells.filter { it.coordinate !in wall }.minBy { getDistance(w, it.coordinate) } }
        .groupBy { it.coordinate }
        .forEach { (k, v) -> actions.add(Spawn(v.size, k)) }
    } else {
      val spawnIn = myCells.filter { !it.inRangeOfRecycler && it.coordinate.x in zoneRange }

      if (DEBUGGING) debug(spawnIn)

      spawnIn.forEach { actions.add(Spawn(1, it.coordinate)) }

      val allBots = cellsWithBots.filter { it.coordinate.x in zoneRange }

      if (DEBUGGING) debug(allBots)

      val targeted = HashSet<Coordinate>(targets.size)

      allBots.forEach { cell ->
        val next = targets.firstOrNull { it.coordinate !in targeted }

        if (next != null) {
          targeted.add(next.coordinate)
          actions.add(Movement(cell.units, cell.coordinate, next.coordinate))
        }
      }
    }

    return actions
  }

  private fun getDistance(from: Coordinate, to: Coordinate): Int {
    val visited = HashSet<Coordinate>(cells.size)
    val queue: Queue<Pair<Coordinate, Int>> = LinkedList()

    queue.add(from to 0)
    visited.add(from)

    while (queue.isNotEmpty()) {
      val (head, distance) = queue.poll()

      if (head == to) return distance

      head.getNeighbors(width, height).filter { !getCell(it).isGrass() && it !in visited }.forEach {
        queue.add(it to distance + 1)
        visited.add(it)
      }
    }

    return Int.MAX_VALUE
  }

  private fun getCell(coordinate: Coordinate): Cell = cells[coordinate] ?: throw Exception("Not found cell at ($coordinate)")

  private fun getBetterRemovals(): List<Cell> {
    val missing : Queue<Coordinate> = PriorityQueue( compareByDescending { it.getNeighbors(width, height).filter { n -> n in toRemove }.size + 1 } )
    val targets = arrayListOf<Coordinate>()
    val removed = arrayListOf<Coordinate>()

    missing.addAll(toRemove)

    while (missing.isNotEmpty()) {
      val next = missing.poll()

      debug(next.getNeighbors(width, height))

      removed.addAll(next.getNeighbors(width, height).filter { it in toRemove } + next)
      missing.removeAll((next.getNeighbors(width, height).filter { it in toRemove } + next).toSet())
      targets.add(next)
      debug(missing)
    }

    return targets.map { getCell(it) }
  }

  private fun findZone() {
    val cellsWithUnits = cells.values.filter { it.isOwn() && it.units > 0 }

    zone = if (cellsWithUnits.sumOf { it.coordinate.x } / cellsWithUnits.size >= width / 2) "RIGHT" else "LEFT"
  }

  private fun calculateWall() {
    var x = (width / 2.0)

    if (width % 2 == 0 && zone == "LEFT") x = floor(x)
    else if (width % 2 != 0) x = floor(x) + 1
    else x += 1

    zoneRange = if (zone == "LEFT") 0 until x.toInt() else (x.toInt() + 1) until width
    wall = (0 until height).map { Coordinate(x.toInt(), it) }.filter { cells.containsKey(it) }
    toRemove = (0 until height)
      .map { Coordinate(x.toInt(), it) }
      .filter { cells.containsKey(it) && !getCell(it).isGrass() && !getCell(it).inRangeOfRecycler && !getCell(it).recycler }
  }

  private fun readMatter() {
    myMatter = input.nextInt()
    opponentMatter = input.nextInt()
  }

  private fun readCells() {
    repeat(height) { y ->
      repeat(width) { x ->
        val cell = cells[Coordinate(x, y)]!!
        cell.scrapAmount = input.nextInt()
        cell.owner = input.nextInt()
        cell.units = input.nextInt()
        cell.recycler = input.nextInt() > 0
        cell.canBuild = input.nextInt() > 0
        cell.canSpawn = input.nextInt() > 0
        cell.inRangeOfRecycler = input.nextInt() > 0
      }
    }
  }
}
