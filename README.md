# osrsx-agility

The **Agility** plugin for [osrsx](https://github.com/osrsx/osrsx-client). Runs an Old School RuneScape
Agility course — every **rooftop**, plus the **Agility Pyramid** — web-walking to the course, traversing
every obstacle in order, collecting what it drops, and keeping run energy topped up, lap after lap.

## What it does

- **Pick a course** (or `Auto — best for level`). The dropdown only lists courses your **Agility level** and
  **world membership** actually qualify for — Draynor (10), Al Kharid (20), Varrock (30), Agility Pyramid (30),
  Canifis (40), Falador (50), Seers' Village (60), Pollnivneach (70), Rellekka (80), Ardougne (90).
  `Auto` only ever picks a rooftop; the Pyramid is opt-in.
- **Traverses the whole course on its own.** The obstacle sequence is **not** a hardcoded coordinate script —
  the plugin recognises obstacles by their menu action (Climb, Cross, Leap, Balance, Hurdle, Vault,
  Swing-across, Teeth-grip, Climb-up/down, …) and learns the course's order on its first lap (see below).
- **Grabs Marks of Grace** as they spawn — but only ones on the roof it can actually reach, so it never
  fixates on a mark across a gap.
- **Manages run energy** automatically, and can lock out physical input while running.
- **Recovers from falls.** Failing an obstacle drops you off the course; the plugin detects it and walks back
  to the start to restart the loop. A general stuck-watchdog does the same for any unexpected snag.
- **Live overlay**: level, XP/hr, laps, and Marks (or Pyramid tops) collected.

## How the course engine works

Rather than script each obstacle by coordinate (brittle when Jagex nudges a tile), the plugin drives itself:

- **Obstacle = the nearest scene object with an Agility action** (matched by whole-word verb tokens, so a
  tree's `Chop down` is never mistaken for `hop`). Ladders/staircases are excluded by name, so a course's own
  `Climb-up` start or `Climb-down` descent is still taken.
- **Commit-until-done.** It commits to one obstacle, approaches until it's on screen (rotating / walking the
  local pathfinder when needed — never stepping onto the obstacle's own tile), clicks it, and waits for the
  full traversal to finish before moving on. A click that produces no movement is cooled down and skipped.
- **A learned coordinate ring** keyed by each obstacle's exact tile (always unique — object ids aren't) fixes
  forward order: lap 1 learns the ring, later laps follow it by index, so duplicate obstacles (e.g. Varrock's
  two `Gap`s) are never re-picked backward. A section where two objects link the same roofs (a back-edge, seen
  on Falador) is detected and avoided.
- Enabling the plugin mid-run just continues forward from wherever you are.

## The Agility Pyramid

The Pyramid is the one course that can't be driven by the rooftop engine, so it has its own:

- **Stairs are obstacles here**, not scenery to skip — and each level's landing sits two tiles from the next
  flight up, so "nearest obstacle" would climb straight to the top past everything worth XP.
- **Every obstacle is a START/END pair.** Clicking an END drops you off the pyramid; only the surveyed START
  tiles are ever clicked.
- **The course spans two regions.** The last flight of the outer pyramid teleports into the upper pyramid at
  a *lower* plane number, so plane-based lap detection can't work.

So it follows a fixed route that was walked and recorded from the live game. Progress is verified by
position — a step counts as done only once you're standing on its known landing tile, and only if you've
actually moved off the previous one, which makes the obstacles that need two clicks self-correcting. The one
exception is the summit's **Climbing rocks**: they pay no XP and leave you in a corridor you could have
walked into, so they're verified against the game's own pyramid-top flag instead. That matters, because
skipping them still lets you "finish" the lap through the doorway with nothing to show for it.

A **lap is counted from the Pyramid top** itself, so a fall (which also dumps you at the base) is never
mistaken for a finished lap. Falls, knock-offs from the rolling Stone block, and enabling the plugin part-way
up all resolve by re-deriving the route position from the nearest landing, or restarting from the base.

When the inventory fills, the **Pyramid tops** setting decides what happens: sell them to Simon Templeton at
the base (10,000 coins each, through his dialogue), drop them, or keep them and carry on for the XP alone.

> The Pyramid sits deep in the Kharidian Desert. Bring desert-heat protection — a Circlet of water, Desert
> amulet 4, or waterskins — the plugin does not manage it.

## Config

| Option | Meaning |
| --- | --- |
| **Course** | Which course to run (or `Auto — best for level`). Filtered to what you qualify for. |
| **Pick up Marks of Grace** | Grab reachable Marks off the roof as they spawn (rooftops only). |
| **Pyramid tops** | Agility Pyramid only: sell the tops to Simon Templeton, drop them, or keep them. |
| **Speed** | Loop pace as a percent (25–400%); higher reacts faster between obstacles. |
| **Lock user input** | Ignore physical mouse/keyboard while running (Antiban). |
| **Stop at level / laps / minutes** | Optional stop conditions (0 = never). |

## Install (in-game marketplace)

Open the **Marketplace** panel in the client, search **Agility**, and click **Install**.

## Build / develop

```bash
./gradlew build            # compile + unit tests
./gradlew installPlugin    # build and drop the jar into ~/.osrsx/plugins for the client
./gradlew -t installPlugin # dev loop: rebuild + reinstall on save (client hot-reloads)
```

Install into a specific launcher account instead:
`-Posrsx.pluginsDir=~/.osrsx/homes/<account>/.osrsx/plugins`.

Built against the published `io.osrsx:osrsx-api` SDK with the `io.osrsx.plugin` Gradle convention plugin —
no engine sources required.
