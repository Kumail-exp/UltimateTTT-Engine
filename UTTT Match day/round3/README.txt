Ultimate Tic-Tac-Toe Bot - Round 3
========================================
Hard time limit: 30 seconds per move (30000 ms)
Default max depth: 28

How to run (Windows):
  Double-click run.bat

  After each game ends, a full transcript is saved in this folder as:
    Game 1.txt
    Game 2.txt
    Game 3.txt
    ... (auto-increments so previous games are never overwritten)

  Note: Windows cannot use ":" in file names, so files are named
  "Game 1.txt" instead of "Game : 1.txt".

How to run (any OS):
  java -jar ultimattt-1.0.0.jar play --limit 30000 --depth 28 --log "Game 1.txt"

Bot features:
  - Opening book (ee main line + follow-ups)
  - Minimax + PVS + TT + killers + history
  - Meta-board evaluation + critical-send ordering
  - Always returns a legal move before the hard deadline
  - Single-threaded
  - Full game transcript logging via --log / run.bat

Source: ultimattt-java/
