# Ultimate Tic-Tac-Toe (Java port) v1.1

## What's new in 1.1
- Stronger AI (better evaluation + move ordering + deeper search)
- Default time limit **5 seconds** per move (stays comfortably under 6 s)
- Interactive prompt at start: choose whether the Bot plays **X** or **O**
- `run.bat` for easy launch on Windows

## Quick start (Windows)

1. Put `ultimattt-1.0.0.jar` and `run.bat` in the same folder
2. Double-click `run.bat` **or** open cmd and run:
   ```
   run.bat
   ```

You will be asked:

```
Who should the BOT play?
  1) Bot plays X  (you play O)
  2) Bot plays O  (you play X)   [default]
  3) Bot vs Bot
```

## Manual run

```bash
java -jar ultimattt-1.0.0.jar          # starts interactive play
java -jar ultimattt-1.0.0.jar play
java -jar ultimattt-1.0.0.jar play -x human -o ai --limit 5000
java -jar ultimattt-1.0.0.jar analyze --depth 12 --limit 5000
```

## Build from source

```bash
mvn package
java -jar target/ultimattt-1.0.0.jar
```

hi lol
## Move notation
Two letters `a`–`i` (local board + square), e.g. `ee` = center of center board.

```
a b c
d e f
g h i
```

## License
Apache-2.0
