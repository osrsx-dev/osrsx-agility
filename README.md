# osrsx-agility

The **Agility** plugin for [osrsx](https://github.com/osrsx/osrsx-client). Runs an Old School RuneScape
**rooftop Agility course**: web-walks to the course, traverses every obstacle in order, picks up Marks of
Grace, and keeps run energy topped up — lap after lap.

## What it does

- **Pick a course** (or `Auto — best for level`). The dropdown only lists rooftops your **Agility level** and
  **world membership** actually qualify for — Draynor (10), Al Kharid (20), Varrock (30), Canifis (40),
  Falador (50), Seers' Village (60), Pollnivneach (70), Rellekka (80), Ardougne (90).
- **Traverses the whole course on its own.** The obstacle sequence is **not** a hardcoded coordinate script —
  the plugin recognises obstacles by their menu action (Climb, Cross, Leap, Balance, Hurdle, Vault,
  Swing-across, Teeth-grip, Climb-up/down, …) and learns the course's order on its first lap (see below).
- **Grabs Marks of Grace** as they spawn — but only ones on the roof it can actually reach, so it never
  fixates on a mark across a gap.
- **Manages run energy** automatically, and can lock out physical input while running.
- **Recovers from falls.** Failing an obstacle drops you off the course; the plugin detects it and walks back
  to the start to restart the loop. A general stuck-watchdog does the same for any unexpected snag.
- **Live overlay**: level, XP/hr, laps and Marks collected.

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

## Config

| Option | Meaning |
| --- | --- |
| **Course** | Which rooftop to run (or `Auto — best for level`). Filtered to what you qualify for. |
| **Pick up Marks of Grace** | Grab reachable Marks off the roof as they spawn. |
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
