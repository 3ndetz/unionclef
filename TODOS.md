# TODOs

- [x] implement Tungsten
  - [x] fixes for autoclef
  - [x] implement
- [x] Create the new merged repo
  - [x] change baritone mojmap to altoclef yarn
    - [x] fix mixins
  - [x] 1.21 runs successfully and working
- [ ] 1. Create a new pathfinder: elite combination of baritone and Tunsten
  - [x] 1.1 Find a suitable name for the new pathfinder
    - autobots theme: Optimus, Bumblebee, Megatron, Starscream, Soundwave, Ironhide, Ratchet, Jazz, Grimlock, Shockwave?
    - ninja turtle theme: Leonardo, Michelangelo, Donatello, Raphael?
    - Solved: "shredder"
  - [x] 2.2 Copy the codebase of baritone TODO
  - [x] 2.3 Implement into project and replace altoclef's baritone calls with this new pathfinder TODO
  - [ ] 2.4 Improve baritone features in shredder
    - [x] Fix stupid debug spam and spam "failed"
    - [x] 2.4.1 Implement ACCELERATION for simple safe paths
      - [x] 2.4.1.1 Implement acceleration for straight line running to run and jump when applicable
      - [ ] 2.4.1.2 Implement diagonal moving acceleration and make diagonal movement instead of horizontal stairs-like movement
        - [ ] 2.4.1.2.1 remove stupid mega-multi-change view path nodes when path is clear and simple without danger and complexity
        - [ ] FAR TODO - unrealizeable. Complex. Can't do normally.
  - [x] 2.5 add safe ENTROPY: HUMAN-like movements
    - [x] 2.5.1 WindMouse camera smoothing in LookBehavior (render-frame, settings: windMouseLook/Gravity/Wind/MaxStep)
    - [x] 2.5.2 TungstenBridge — smart delegation of simple flat segments to tungsten (settings: useTungsten, tungstenMinSegment)
  - [ ] 2.6 Implement tungsten into this pathfinder (deeper integration beyond flat segments)
  - [x] 2.7 Fix jump bridging (jumpBridging setting)
    - [x] 2.7.1 Rewrite state machine: sprint-speed telly bridge (FJ_SPRINT → FJ_AIRBORNE continuous)
    - [x] 2.7.2 Fix placement: processRightClickBlock bypasses crosshair (objectMouseOver MISS at 86°+)
    - [x] 2.7.3 Fix sprint: setSprinting(true) forces sprint at entity level
    - [x] 2.7.4 Fix TestBridgingCommand: GoalBlock at player Y (was GoalXZ → pathfinder descended)
    - [x] 2.7.5 Optimize: debug flag, drift correction, cooldown, path-end graceful exit
<!-- Верхнеуровневые задачи. Пишет юзер, AI отмечает выполнение. -->
<!-- Формат: - [ ] задача / - [x] задача -->
